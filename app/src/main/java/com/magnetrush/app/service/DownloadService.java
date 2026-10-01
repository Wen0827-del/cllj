package com.magnetrush.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.magnetrush.app.R;
import com.magnetrush.app.core.TaskStatus;
import com.magnetrush.app.core.TaskStore;
import com.magnetrush.app.core.TorrentEngine;
import com.magnetrush.app.core.TorrentTask;
import com.magnetrush.app.ui.MainActivity;
import com.magnetrush.app.ui.TaskDetailActivity;
import com.magnetrush.app.util.Fmt;
import com.magnetrush.app.util.Settings;

import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 下载引擎的宿主服务。
 *
 * <p>它负责：
 * <ul>
 *   <li>持有 {@link TorrentEngine} 单例并在前台运行，保证后台/锁屏不被杀；</li>
 *   <li>每秒把 libtorrent 的实时状态合并成 {@link TorrentView} 广播给界面；</li>
 *   <li>定时把 resume data 落盘，实现断点续传；</li>
 *   <li>常驻通知 + 通知栏暂停/继续/取消按钮。</li>
 * </ul>
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    // ---------------- Intent action ----------------
    public static final String ACTION_ADD_MAGNET = "com.magnetrush.app.ADD_MAGNET";
    public static final String ACTION_ADD_TORRENT = "com.magnetrush.app.ADD_TORRENT";
    public static final String ACTION_RESUME_ALL = "com.magnetrush.app.RESUME_ALL";
    public static final String ACTION_PAUSE_ALL = "com.magnetrush.app.PAUSE_ALL";
    public static final String ACTION_TASK_PAUSE = "com.magnetrush.app.TASK_PAUSE";
    public static final String ACTION_TASK_RESUME = "com.magnetrush.app.TASK_RESUME";
    public static final String ACTION_TASK_CANCEL = "com.magnetrush.app.TASK_CANCEL";
    public static final String ACTION_SHUTDOWN_ENGINE = "com.magnetrush.app.SHUTDOWN_ENGINE";

    public static final String EXTRA_MAGNET = "magnet";
    public static final String EXTRA_TORRENT_PATH = "torrent_path";
    public static final String EXTRA_HASH = "hash";
    public static final String EXTRA_SEQUENTIAL = "sequential";
    public static final String EXTRA_START_NOW = "start_now";
    /** 取消任务时是否连磁盘文件一起删。默认 true。 */
    public static final String EXTRA_REMOVE_FILES = "remove_files";

    // ---------------- 广播给界面 ----------------
    public static final String BROADCAST_STATE = "com.magnetrush.app.STATE";
    public static final String EXTRA_TASKS_JSON = "tasks_json";
    public static final String EXTRA_SESSION_DOWN = "session_down";
    public static final String EXTRA_SESSION_UP = "session_up";
    public static final String EXTRA_DHT_NODES = "dht_nodes";
    public static final String EXTRA_ENGINE_ERROR = "engine_error";

    // ---------------- 通知 ----------------
    private static final String CH_PROGRESS = "channel_progress";
    private static final String CH_DONE = "channel_done";
    private static final String CH_ERROR = "channel_error";
    private static final int NOTIF_PROGRESS = 1001;
    private static final int NOTIF_DONE_BASE = 2000;
    private static final int NOTIF_ERROR_BASE = 3000;

    private static final long TICK_MS = 1000L;
    /** 每 N 次 tick 存一次 resume data（约 15 秒） */
    private static final int RESUME_EVERY_TICKS = 15;

    private TorrentEngine engine;
    private TaskStore store;
    private Settings settings;

    private ScheduledExecutorService ticker;
    private volatile boolean ticking = false;

    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    /** infoHash -> 最新快照 */
    private final Map<String, TorrentView> views = new LinkedHashMap<>();
    /** 已经发过「完成」通知的任务 */
    private final Set<String> doneNotified = new HashSet<>();

    private int tickCount = 0;
    private volatile boolean wifiOnlyBlocked = false;
    private String engineError;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyWifiOnlyPolicy();
        }
    };

    // ================================================================ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        engine = TorrentEngine.get();
        store = TaskStore.get(this);
        settings = Settings.get(this);

        createNotificationChannels();
        startForegroundCompat(buildIdleNotification());

        acquireLocks();

        engine.setListener(new TorrentEngine.Listener() {
            @Override
            public void onMetadataReceived(TorrentHandle handle, TorrentInfo info) {
                onMetadata(handle, info);
            }

            @Override
            public void onTorrentFinished(TorrentHandle handle) {
                DownloadService.this.onTorrentFinished(handle);
            }

            @Override
            public void onResumeDataReady(TorrentHandle handle, byte[] resumeData) {
                store.saveResumeData(handle.infoHash().toHex(), resumeData);
            }

            @Override
            public void onEngineError(String message) {
                engineError = message;
                postErrorNotification(getString(R.string.app_name), message);
            }

            @Override
            public void onStatusDirty() {
                // 下一次 tick 自然会取到；不额外触发以省电
            }
        });

        File resumeDir = store.resumeDir();
        engine.start(getApplicationContext(), resumeDir);
        engine.setDownloadLimitKbps(settings.downloadLimitKbps());
        engine.setUploadLimitKbps(settings.uploadLimitKbps());

        // 网络变化时重新判断「仅 Wi-Fi」
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);

        startTicker();

        // 会话就绪后恢复上次没下完的任务
        mainHandler.postDelayed(this::restorePendingTasks, 1200);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null) {
            handleIntent(intent);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTicker();
        try {
            unregisterReceiver(networkReceiver);
        } catch (Throwable ignored) {
        }
        // 退出前把断点存下来，下次启动才能续传
        saveAllResumeDataBlocking();
        persistViews();
        releaseLocks();
        super.onDestroy();
    }

    // ================================================================ Intent

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case ACTION_ADD_MAGNET: {
                String magnet = intent.getStringExtra(EXTRA_MAGNET);
                boolean seq = intent.getBooleanExtra(EXTRA_SEQUENTIAL, settings.sequentialDefault());
                boolean startNow = intent.getBooleanExtra(EXTRA_START_NOW, true);
                if (magnet != null && !magnet.trim().isEmpty()) {
                    addMagnet(magnet.trim(), seq, startNow);
                }
                break;
            }
            case ACTION_TASK_PAUSE: {
                String hash = intent.getStringExtra(EXTRA_HASH);
                if (hash != null) {
                    engine.requestResumeData(hash, (h, data) ->
                            store.saveResumeData(h.infoHash().toHex(), data));
                    engine.pause(hash);
                    TorrentTask t = store.get(hash);
                    if (t != null) {
                        t.state = TorrentTask.State.PAUSED;
                        store.put(t);
                    }
                    store.flush();
                    forceTick();
                }
                break;
            }
            case ACTION_TASK_RESUME: {
                String hash = intent.getStringExtra(EXTRA_HASH);
                if (hash != null) {
                    // 会话里已经没有这个 handle 时（进程重启过），走一次完整恢复
                    if (!engine.exists(hash)) {
                        restoreOne(hash);
                    } else {
                        engine.resume(hash);
                    }
                    TorrentTask t = store.get(hash);
                    if (t != null) {
                        t.state = TorrentTask.State.DOWNLOADING;
                        t.lastError = null;
                        store.put(t);
                    }
                    forceTick();
                }
                break;
            }
            case ACTION_ADD_TORRENT: {
                String path = intent.getStringExtra(EXTRA_TORRENT_PATH);
                boolean seq = intent.getBooleanExtra(EXTRA_SEQUENTIAL, settings.sequentialDefault());
                if (path != null && !path.isEmpty()) {
                    addTorrentFile(new File(path), seq);
                }
                break;
            }
            case ACTION_TASK_CANCEL: {
                String hash = intent.getStringExtra(EXTRA_HASH);
                if (hash != null) {
                    cancelTask(hash, intent.getBooleanExtra(EXTRA_REMOVE_FILES, true));
                }
                break;
            }
            case ACTION_RESUME_ALL: {
                for (TorrentTask t : store.all()) {
                    engine.resume(t.infoHash);
                }
                forceTick();
                break;
            }
            case ACTION_PAUSE_ALL: {
                for (TorrentTask t : store.all()) {
                    engine.pause(t.infoHash);
                }
                forceTick();
                break;
            }
            case ACTION_SHUTDOWN_ENGINE: {
                engine.shutdown();
                break;
            }
            default:
                break;
        }
    }

    // ================================================================ 添加 / 恢复

    private void addMagnet(String magnet, boolean sequential, boolean startNow) {
        File dir = settings.saveDir(this);
        String hash = engine.addMagnet(magnet, dir, sequential, startNow);
        if (hash == null) {
            postErrorNotification(getString(R.string.add_failed_title),
                    getString(R.string.add_failed_msg));
            return;
        }
        TorrentTask task = store.get(hash);
        if (task == null) {
            task = new TorrentTask();
            task.infoHash = hash;
            task.magnet = magnet;
            task.name = guessNameFromMagnet(magnet, hash);
            task.savePath = dir.getAbsolutePath();
            task.sequential = sequential;
            task.startImmediately = startNow;
            task.state = startNow ? TorrentTask.State.DOWNLOADING : TorrentTask.State.PAUSED;
            store.put(task);
        }
        forceTick();
    }

    /** 从 .torrent 文件添加任务 */
    private void addTorrentFile(File torrentFile, boolean sequential) {
        File dir = settings.saveDir(this);
        final String path = torrentFile.getAbsolutePath();
        final String cachePrefix = getCacheDir().getAbsolutePath();
        byte[] bytes;
        try {
            bytes = readFileBytes(torrentFile);
        } catch (Throwable e) {
            postErrorNotification(getString(R.string.add_failed_title),
                    getString(R.string.toast_file_read_failed));
            return;
        }
        if (bytes == null || bytes.length == 0) {
            postErrorNotification(getString(R.string.add_failed_title),
                    getString(R.string.toast_file_read_failed));
            return;
        }
        String name = torrentFile.getName();
        String hash = engine.addTorrentFile(bytes, dir, sequential, true);
        // 用完即删的临时文件（界面从 SAF 读出来后落到 cache 的那个）
        if (path.startsWith(cachePrefix)) {
            //noinspection ResultOfMethodCallIgnored
            torrentFile.delete();
        }
        if (hash == null) {
            postErrorNotification(getString(R.string.add_failed_title),
                    getString(R.string.add_failed_msg));
            return;
        }
        TorrentTask task = store.get(hash);
        if (task == null) {
            task = new TorrentTask();
            task.infoHash = hash;
            task.savePath = dir.getAbsolutePath();
            task.sequential = sequential;
            task.state = TorrentTask.State.DOWNLOADING;
            // 存一份种子字节，重启后才能精确恢复这个任务
            task.torrentBytes = bytes;
            // 真名等元数据回调里补上，这里先用文件名顶着
            task.name = name.endsWith(".torrent")
                    ? name.substring(0, name.length() - ".torrent".length()) : name;
            store.put(task);
        }
        forceTick();
    }

    private static byte[] readFileBytes(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** 从磁力链接的 dn 参数猜个名字，让列表不至于显示一长串 hash */
    private static String guessNameFromMagnet(String magnet, String hash) {
        try {
            String query = magnet;
            int q = magnet.indexOf('?');
            if (q >= 0) {
                query = magnet.substring(q + 1);
            }
            for (String pair : query.split("&")) {
                if (pair.startsWith("dn=")) {
                    String dn = Uri.decode(pair.substring(3)).trim();
                    if (!dn.isEmpty()) {
                        return dn;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return hash.substring(0, Math.min(12, hash.length()));
    }

    /** 进程重启后，把所有非完成的任务重新挂回会话 */
    private void restorePendingTasks() {
        List<TorrentTask> all = store.all();
        for (TorrentTask t : all) {
            if (t.isCompleted()) {
                continue;
            }
            restoreOne(t.infoHash);
        }
    }

    private void restoreOne(String hash) {
        TorrentTask task = store.get(hash);
        if (task == null) {
            return;
        }
        if (engine.exists(hash)) {
            if (!task.isCompleted()) {
                engine.resume(hash);
            }
            return;
        }
        File dir = task.savePath != null && !task.savePath.isEmpty()
                ? new File(task.savePath)
                : settings.saveDir(this);
        boolean paused = task.state == TorrentTask.State.PAUSED;

        // 恢复路径优先用磁力链接（最常见，且 libtorrent 会复用磁盘上校验通过的分片）；
        // 用 .torrent 添加的任务没有磁链，就退回用保存下来的种子字节。
        // 两条路都不会重下已经下好的数据。
        boolean ok = false;
        if (task.magnet != null && !task.magnet.isEmpty()) {
            ok = engine.restore(hash, task.magnet, dir);
        }
        if (!ok && task.torrentBytes != null && task.torrentBytes.length > 0) {
            ok = engine.addTorrentFile(task.torrentBytes, dir, task.sequential, false) != null;
        }

        if (ok) {
            if (paused) {
                engine.pause(hash);
            } else {
                engine.resume(hash);
            }
            if (task.filePriorities != null && task.filePriorities.length > 0) {
                engine.setFilePriorities(hash, task.filePriorities);
            }
        } else if ((task.magnet == null || task.magnet.isEmpty())
                && (task.torrentBytes == null || task.torrentBytes.length == 0)) {
            // 既没有磁链也没有种子字节，确实没法恢复了
            task.state = TorrentTask.State.ERROR;
            task.lastError = getString(R.string.error_no_source);
            store.put(task);
        }
    }

    private void cancelTask(String hash, boolean removeFiles) {
        TorrentTask t = store.get(hash);
        engine.remove(hash, removeFiles);
        if (t != null && removeFiles) {
            deleteTaskData(t);
        }
        store.remove(hash);
        synchronized (views) {
            views.remove(hash);
        }
        doneNotified.remove(hash);
        NotificationManagerCompat.from(this).cancel(notifIdFor(hash));
        forceTick();
    }

    /** 删除任务对应的磁盘数据（在 savePath 下按种子名字找） */
    private void deleteTaskData(TorrentTask t) {
        try {
            if (t.savePath == null || t.name == null) {
                return;
            }
            File root = new File(t.savePath);
            File target = new File(root, t.name);
            // 安全校验：确保要删的路径确实在保存目录里面，避免路径穿越
            if (!target.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                Log.w(TAG, "refuse to delete outside save dir: " + target);
                return;
            }
            deleteRecursively(target);
        } catch (Throwable e) {
            Log.w(TAG, "deleteTaskData failed", e);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ================================================================ 引擎回调

    private void onMetadata(TorrentHandle handle, TorrentInfo info) {
        String hash = handle.infoHash().toHex();
        TorrentTask task = store.get(hash);
        if (task != null) {
            task.name = info.name();
            task.totalBytes = info.totalSize();
            if (task.state == TorrentTask.State.QUEUED) {
                task.state = TorrentTask.State.DOWNLOADING;
            }
            store.put(task);
            if (task.filePriorities != null && task.filePriorities.length > 0) {
                engine.setFilePriorities(hash, task.filePriorities);
            }
        }
        forceTick();
    }

    private void onTorrentFinished(TorrentHandle handle) {
        String hash = handle.infoHash().toHex();
        TorrentTask task = store.get(hash);
        if (task != null) {
            task.state = TorrentTask.State.COMPLETED;
            task.completedAt = System.currentTimeMillis();
            task.downloadedBytes = handle.status().totalDone();
            long total = handle.status().total();
            if (total > 0) {
                task.totalBytes = total;
            }
            store.put(task);

            // 默认下完就停，省电省流量；想在设置里打开继续做种
            if (!settings.seedAfterFinish()) {
                engine.pause(hash);
            }
        }
        store.flush();

        if (doneNotified.add(hash)) {
            postDoneNotification(hash, task != null ? task.name : hash, task != null ? task.savePath : null);
            scanCompletedFiles(task);
        }
        forceTick();
    }

    /** Android 9 及以下：把完成的文件通知媒体库，否则文件管理器里看不到 */
    private void scanCompletedFiles(TorrentTask task) {
        if (task == null || task.savePath == null || task.name == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;     // 分区存储下系统自己会扫
        }
        try {
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scan.setData(Uri.fromFile(new File(task.savePath, task.name)));
            sendBroadcast(scan);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 时钟

    private void startTicker() {
        if (ticking) {
            return;
        }
        ticking = true;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "download-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tickSafely, 300, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopTicker() {
        ticking = false;
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    private void forceTick() {
        ScheduledExecutorService t = ticker;
        if (t != null && !t.isShutdown()) {
            t.execute(this::tickSafely);
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable e) {
            Log.w(TAG, "tick failed", e);
        }
    }

    private void tick() {
        tickCount++;

        // 引擎还没起来时也要让界面知道任务列表存在
        Map<String, TaskStatus> live = engine.isReady()
                ? engine.allStatuses()
                : Collections.emptyMap();

        List<TorrentTask> tasks = store.sortedForDisplay();
        Map<String, TorrentView> next = new LinkedHashMap<>();
        long downSum = 0L;
        long upSum = 0L;

        for (TorrentTask task : tasks) {
            TorrentView v = new TorrentView();
            v.infoHash = task.infoHash;
            v.name = task.name != null ? task.name : task.infoHash.substring(0, Math.min(12, task.infoHash.length()));
            v.savePath = task.savePath;
            v.sequential = task.sequential;
            v.completed = task.isCompleted();
            v.completedAt = task.completedAt;
            v.totalBytes = task.totalBytes;
            v.downloadedBytes = task.downloadedBytes;

            TaskStatus st = live.get(task.infoHash);
            if (st != null) {
                v.state = st.state.name();
                v.progress = st.progress;
                v.downloadRate = st.downloadRate;
                v.uploadRate = st.uploadRate;
                v.downloadedBytes = st.downloadedBytes;
                v.uploadedBytes = st.uploadedBytes;
                if (st.totalBytes > 0) {
                    v.totalBytes = st.totalBytes;
                }
                v.peers = st.peers;
                v.seeds = st.seeds;
                v.fileCount = st.fileCount;
                v.sequential = st.sequential;
                v.error = st.errorMessage;

                // 实时值回写任务表（低频落盘，见下面的 flush）
                task.downloadedBytes = st.downloadedBytes;
                if (st.totalBytes > 0) {
                    task.totalBytes = st.totalBytes;
                }
                if (st.name != null && !st.name.isEmpty() && !st.name.equals(task.name)) {
                    task.name = st.name;
                }
                if (st.state == TaskStatus.State.FINISHED || st.state == TaskStatus.State.SEEDING) {
                    if (!task.isCompleted()) {
                        task.state = TorrentTask.State.COMPLETED;
                        task.completedAt = System.currentTimeMillis();
                    }
                } else if (st.state == TaskStatus.State.ERROR) {
                    task.state = TorrentTask.State.ERROR;
                    task.lastError = st.errorMessage;
                } else if (st.state == TaskStatus.State.PAUSED) {
                    task.state = TorrentTask.State.PAUSED;
                } else if (task.state != TorrentTask.State.COMPLETED) {
                    task.state = TorrentTask.State.DOWNLOADING;
                }
                store.updateInMemory(task);
            } else {
                // 会话里还没有这个任务（等元数据 / 进程刚重启）
                switch (task.state) {
                    case COMPLETED:
                        v.state = TaskStatus.State.FINISHED.name();
                        v.progress = 1f;
                        break;
                    case PAUSED:
                        v.state = TaskStatus.State.PAUSED.name();
                        break;
                    case ERROR:
                        v.state = TaskStatus.State.ERROR.name();
                        v.error = task.lastError;
                        break;
                    default:
                        v.state = TaskStatus.State.FETCHING_METADATA.name();
                        break;
                }
            }
            downSum += v.downloadRate;
            upSum += v.uploadRate;
            next.put(task.infoHash, v);
        }

        synchronized (views) {
            views.clear();
            views.putAll(next);
        }

        // 周期性把 resume data 和任务表落盘
        if (tickCount % RESUME_EVERY_TICKS == 0) {
            for (TorrentView v : next.values()) {
                if (v.isRunning() && v.stateEnum() == TaskStatus.State.DOWNLOADING) {
                    final String hash = v.infoHash;
                    engine.requestResumeData(hash, (h, data) -> store.saveResumeData(hash, data));
                }
            }
            store.flush();
        }

        broadcastState(downSum, upSum);
        updateNotification(downSum, upSum);
    }

    private void persistViews() {
        store.flush();
    }

    private void broadcastState(long downSum, long upSum) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (views) {
                for (TorrentView v : views.values()) {
                    arr.put(v.toJson());
                }
            }
            Intent i = new Intent(BROADCAST_STATE);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_TASKS_JSON, arr.toString());
            i.putExtra(EXTRA_SESSION_DOWN, downSum);
            i.putExtra(EXTRA_SESSION_UP, upSum);
            i.putExtra(EXTRA_DHT_NODES, engine.dhtNodes());
            if (engineError != null) {
                i.putExtra(EXTRA_ENGINE_ERROR, engineError);
            }
            sendBroadcast(i);
        } catch (Throwable e) {
            Log.w(TAG, "broadcast failed", e);
        }
    }

    // ================================================================ 仅 Wi-Fi

    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * 「仅 Wi-Fi」策略：不满足条件时把所有任务暂停，条件满足后再恢复。
     * 这里只动 libtorrent，不改任务表里的用户意图（PAUSED 状态）；
     * 用户手动暂停的任务不会被这里自动恢复。
     */
    private void applyWifiOnlyPolicy() {
        if (!settings.wifiOnly()) {
            if (wifiOnlyBlocked) {
                wifiOnlyBlocked = false;
                for (TorrentTask t : store.all()) {
                    if (!t.isCompleted() && t.state != TorrentTask.State.PAUSED) {
                        engine.resume(t.infoHash);
                    }
                }
            }
            return;
        }
        boolean wifi = isOnWifi();
        if (!wifi && !wifiOnlyBlocked) {
            wifiOnlyBlocked = true;
            for (TorrentTask t : store.all()) {
                if (!t.isCompleted()) {
                    engine.pause(t.infoHash);
                }
            }
        } else if (wifi && wifiOnlyBlocked) {
            wifiOnlyBlocked = false;
            for (TorrentTask t : store.all()) {
                if (!t.isCompleted() && t.state != TorrentTask.State.PAUSED) {
                    engine.resume(t.infoHash);
                }
            }
        }
    }

    // ================================================================ 唤醒锁

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagnetRush:download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable e) {
            Log.w(TAG, "wake lock failed", e);
        }
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("MagnetRush:lsd");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable e) {
            Log.w(TAG, "multicast lock failed", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 断点

    private void saveAllResumeDataBlocking() {
        try {
            for (TorrentTask t : store.all()) {
                if (t.isCompleted()) {
                    continue;
                }
                final String hash = t.infoHash;
                engine.requestResumeData(hash, (h, data) -> store.saveResumeData(hash, data));
            }
            engine.awaitIdle(2500);
            store.flush();
        } catch (Throwable e) {
            Log.w(TAG, "saveAllResumeData failed", e);
        }
    }

    // ================================================================ 通知

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel progress = new NotificationChannel(
                CH_PROGRESS,
                getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        progress.setSound(null, null);
        nm.createNotificationChannel(progress);

        NotificationChannel done = new NotificationChannel(
                CH_DONE,
                getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);

        NotificationChannel error = new NotificationChannel(
                CH_ERROR,
                getString(R.string.channel_error),
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(error);
    }

    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, pendingFlags());
    }

    private PendingIntent detailIntent(String hash) {
        Intent i = new Intent(this, TaskDetailActivity.class);
        i.putExtra(TaskDetailActivity.EXTRA_HASH, hash);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, hash.hashCode() & 0x7fffffff, i, pendingFlags());
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private Notification buildIdleNotification() {
        return new NotificationCompat.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_idle))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_PROGRESS, n);
            }
        } catch (Throwable e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void updateNotification(long downSum, long upSum) {
        if (!settings.notifyProgress()) {
            return;
        }
        List<TorrentView> snapshot;
        synchronized (views) {
            snapshot = new ArrayList<>(views.values());
        }

        int active = 0;
        int done = 0;
        TorrentView firstActive = null;
        long totalBytes = 0L;
        long doneBytes = 0L;

        for (TorrentView v : snapshot) {
            if (v.completed) {
                done++;
            } else if (v.isRunning()) {
                active++;
                if (firstActive == null) {
                    firstActive = v;
                }
            }
            if (v.totalBytes > 0) {
                totalBytes += v.totalBytes;
                doneBytes += Math.min(v.downloadedBytes, v.totalBytes);
            }
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (active == 0 && done == 0) {
            b.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notif_idle));
        } else if (active == 0) {
            b.setContentTitle(getString(R.string.notif_all_done))
                    .setContentText(getString(R.string.notif_done_count, done));
        } else {
            String title = firstActive != null && firstActive.name != null
                    ? firstActive.name
                    : getString(R.string.app_name);
            if (active > 1) {
                title = title + "  (+" + (active - 1) + ")";
            }
            b.setContentTitle(title);

            StringBuilder sb = new StringBuilder();
            sb.append(Fmt.speed(downSum)).append(" ↓");
            if (upSum > 0) {
                sb.append("   ").append(Fmt.speed(upSum)).append(" ↑");
            }
            if (firstActive != null) {
                sb.append("   ").append(Fmt.percent(firstActive.progress));
            }
            b.setContentText(sb.toString());

            if (firstActive != null) {
                b.setProgress(1000, (int) (Math.max(0f, Math.min(1f, firstActive.progress)) * 1000f), false);
            } else if (totalBytes > 0) {
                b.setProgress(1000, (int) (doneBytes * 1000L / totalBytes), false);
            }
        }

        // 全部暂停 / 全部继续
        boolean anyRunning = active > 0;
        Intent toggle = new Intent(this, NotificationActionReceiver.class);
        toggle.setAction(anyRunning ? ACTION_PAUSE_ALL : ACTION_RESUME_ALL);
        b.addAction(0,
                getString(anyRunning ? R.string.action_pause_all : R.string.action_resume_all),
                PendingIntent.getBroadcast(this, anyRunning ? 1 : 2, toggle, pendingFlags()));

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        try {
            nm.notify(NOTIF_PROGRESS, b.build());
        } catch (SecurityException ignored) {
            // 用户没给通知权限（Android 13+），静默跳过
        }
    }

    private void postDoneNotification(String hash, String name, String savePath) {
        Intent open = new Intent(this, TaskDetailActivity.class);
        open.putExtra(TaskDetailActivity.EXTRA_HASH, hash);
        PendingIntent pi = PendingIntent.getActivity(
                this, (hash + "done").hashCode() & 0x7fffffff, open, pendingFlags());

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_done)
                .setContentTitle(getString(R.string.notif_finished_title))
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (savePath != null) {
            b.setSubText(savePath);
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_DONE_BASE + (hash.hashCode() & 0xff), b.build());
        } catch (SecurityException ignored) {
        }
    }

    private void postErrorNotification(String title, String message) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ERROR)
                .setSmallIcon(R.drawable.ic_stat_warn)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            NotificationManagerCompat.from(this).notify(
                    NOTIF_ERROR_BASE + (Math.abs(title.hashCode()) & 0xff), b.build());
        } catch (SecurityException ignored) {
        }
    }

    private static int notifIdFor(String hash) {
        return NOTIF_DONE_BASE + (hash.hashCode() & 0xff);
    }

    // ================================================================ 静态入口

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, DownloadService.class);
        startWithIntent(ctx, i);
    }

    /** 把任意带 action 的 Intent 送进服务（供通知接收器复用） */
    public static void dispatch(Context ctx, Intent i) {
        if (i == null) {
            return;
        }
        i.setClass(ctx, DownloadService.class);
        i.putExtra(EXTRA_START_NOW, true);
        startWithIntent(ctx, i);
    }

    public static void addMagnet(Context ctx, String magnet, boolean sequential) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_ADD_MAGNET);
        i.putExtra(EXTRA_MAGNET, magnet);
        i.putExtra(EXTRA_SEQUENTIAL, sequential);
        i.putExtra(EXTRA_START_NOW, true);
        startWithIntent(ctx, i);
    }

    /** 组装「添加 .torrent 文件」的 Intent（供界面调用） */
    public static Intent buildAddTorrentIntent(Context ctx, String absolutePath, boolean sequential) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_ADD_TORRENT);
        i.putExtra(EXTRA_TORRENT_PATH, absolutePath);
        i.putExtra(EXTRA_SEQUENTIAL, sequential);
        i.putExtra(EXTRA_START_NOW, true);
        return i;
    }

    public static void pauseTask(Context ctx, String hash) {
        sendAction(ctx, ACTION_TASK_PAUSE, hash);
    }

    public static void resumeTask(Context ctx, String hash) {
        sendAction(ctx, ACTION_TASK_RESUME, hash);
    }

    public static void cancelTask(Context ctx, String hash) {
        sendAction(ctx, ACTION_TASK_CANCEL, hash);
    }

    /** 删除任务，同时删掉已下载的文件 */
    public static void cancelTaskWithFiles(Context ctx, String hash) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_TASK_CANCEL);
        i.putExtra(EXTRA_HASH, hash);
        i.putExtra(EXTRA_REMOVE_FILES, true);
        startWithIntent(ctx, i);
    }

    /** 只从列表里移除任务，磁盘上的文件保留 */
    public static void cancelTaskKeepFiles(Context ctx, String hash) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_TASK_CANCEL);
        i.putExtra(EXTRA_HASH, hash);
        i.putExtra(EXTRA_REMOVE_FILES, false);
        startWithIntent(ctx, i);
    }

    private static void sendAction(Context ctx, String action, String hash) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(action);
        i.putExtra(EXTRA_HASH, hash);
        startWithIntent(ctx, i);
    }

    private static void startWithIntent(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable e) {
            Log.w(TAG, "send action failed: " + i.getAction(), e);
        }
    }

    /** 保存目录是否可用（供界面提前提示） */
    public static boolean isExternalStorageReady() {
        try {
            return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        } catch (Throwable t) {
            return false;
        }
    }
}
