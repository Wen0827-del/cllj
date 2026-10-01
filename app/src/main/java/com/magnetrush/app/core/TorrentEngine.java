package com.magnetrush.app.core;

import android.content.Context;
import android.util.Log;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.StorageMode;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.FileErrorAlert;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * libtorrent 会话的单例封装 —— 全应用只允许存在一个 {@link SessionManager}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有 libtorrent 调用都投递到同一条单线程队列执行，避免 JNI 层的多线程竞争；</li>
 *   <li>用 Alert 机制异步收「元数据到手 / 出错 / 下载完成」事件，再回调给 {@link Listener}；</li>
 *   <li>速度调优全部集中在 {@link #buildOptimizedSettings()}，想继续压榨带宽只改那一个方法。</li>
 * </ul>
 *
 * <p>本类基于 <b>org.libtorrent4j</b> 分支（libtorrent 2.0.x 内核）。
 */
public final class TorrentEngine {

    private static final String TAG = "TorrentEngine";

    /** 兜底 Tracker：任何网络环境下都能靠它们找到第一批节点 */
    private static final String[] EXTRA_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.demonii.com:1337/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "wss://tracker.openwebtorrent.com:443/announce"
    };

    /**
     * DHT 引导节点，格式 {@code host:port} 逗号分隔。
     *
     * <p>这里加的是「兜底」节点：libtorrent 自己会去解析 router.bittorrent.com 这类
     * 域名，但某些路由器/运营商环境下 DNS 解析会失败，多写几个能明显提高磁力链接的
     * 冷启动成功率。
     */
    private static final String DHT_BOOTSTRAP =
            "router.bittorrent.com:6881,"
                    + "router.utorrent.com:6881,"
                    + "dht.transmissionbt.com:6881,"
                    + "dht.libtorrent.org:25401,"
                    + "router.bitcomet.com:6881";

    // ------------------------------------------------------------------ 回调

    /** 引擎向外部（服务层）抛事件 */
    public interface Listener {
        /** 磁力链接的元数据到手了，此时才能拿到文件名 / 文件列表 */
        void onMetadataReceived(TorrentHandle handle, TorrentInfo info);

        /** 某个任务下载完成（数据校验也通过了） */
        void onTorrentFinished(TorrentHandle handle);

        /** libtorrent 主动给出了 resume data，应立刻落盘以保证断点续传 */
        void onResumeDataReady(TorrentHandle handle, byte[] resumeData);

        /** 引擎侧出现问题 */
        void onEngineError(String message);

        /** 需要立刻刷新状态 */
        void onStatusDirty();
    }

    // ------------------------------------------------------------------ 字段

    private static volatile TorrentEngine instance;

    private final ScheduledExecutorService commandQueue =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "torrent-engine");
                t.setDaemon(true);
                return t;
            });

    private SessionManager session;
    private volatile Listener listener;

    /** 已经拿到名字的任务，避免 UI 上一直显示 magnet:?xt=... 这一长串 */
    private final Map<Sha1Hash, String> knownNames = new ConcurrentHashMap<>();
    /** 每个任务最近一次错误信息 */
    private final Map<Sha1Hash, String> errorMessages = new ConcurrentHashMap<>();
    /** 已经处理过 finished 事件的 infoHash，防止重复回调 */
    private final Set<Sha1Hash> finishedNotified = Collections.synchronizedSet(new HashSet<>());
    /** 等待 resume data 落盘的回调 */
    private final Map<Sha1Hash, BiConsumer<TorrentHandle, byte[]>> resumeCallbacks =
            new ConcurrentHashMap<>();
    /** 已发出但还没回来的 resume data 请求 */
    private final Set<Sha1Hash> resumeRequested = Collections.synchronizedSet(new HashSet<>());
    /** 会话就绪前先排队的操作 */
    private final ConcurrentLinkedQueue<Runnable> pendingWhenReady = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile boolean started = false;

    private TorrentEngine() {
    }

    public static TorrentEngine get() {
        TorrentEngine local = instance;
        if (local == null) {
            synchronized (TorrentEngine.class) {
                local = instance;
                if (local == null) {
                    local = new TorrentEngine();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------- 启动 / 关闭

    /**
     * 启动会话。任意线程可调用，重复调用无效。
     *
     * @param appContext Application Context
     * @param resumeDir  resume data 目录（建议放在 getExternalFilesDir(null)）
     */
    public void start(final Context appContext, final File resumeDir) {
        commandQueue.execute(() -> {
            if (!started) {
                doStart(appContext, resumeDir);
            }
        });
    }

    private void doStart(Context appContext, File resumeDir) {
        try {
            if (resumeDir != null && !resumeDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                resumeDir.mkdirs();
            }

            SettingsPack sp = buildOptimizedSettings();

            session = new SessionManager();
            // 注意：2.1.0 起 start() 收的是 SessionParams，不是 SettingsPack
            session.start(new SessionParams(sp));

            session.addListener(buildAlertListener());

            ready.set(true);
            started = true;

            Runnable r;
            while ((r = pendingWhenReady.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "deferred task failed", t);
                }
            }
            Log.i(TAG, "engine started, dht=" + sp.isEnableDht()
                    + ", connLimit=" + sp.connectionsLimit());
        } catch (Throwable t) {
            Log.e(TAG, "engine start failed", t);
            notifyEngineError("内核启动失败：" + t);
        }
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean isRunning() {
        return started && session != null && session.isRunning();
    }

    private void whenReady(Runnable r) {
        if (ready.get()) {
            r.run();
        } else {
            pendingWhenReady.add(r);
        }
    }

    /** 关闭会话：先给所有任务要一次 resume data，再退出，保证下次能续传 */
    public void shutdown() {
        commandQueue.execute(() -> {
            try {
                if (session != null && started) {
                    for (TorrentHandle h : safeHandles()) {
                        try {
                            h.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
                        } catch (Throwable ignored) {
                        }
                    }
                    Thread.sleep(500);      // 给 libtorrent 一点时间把 alert 吐出来
                    session.stop();
                }
            } catch (Throwable t) {
                Log.w(TAG, "shutdown", t);
            } finally {
                started = false;
                ready.set(false);
            }
        });
    }

    /** 彻底释放引擎（进程退出前调用） */
    public void release() {
        commandQueue.execute(() -> {
            try {
                if (session != null) {
                    session.stop();
                }
            } catch (Throwable ignored) {
            }
            session = null;
            started = false;
            ready.set(false);
            knownNames.clear();
            errorMessages.clear();
            resumeCallbacks.clear();
            resumeRequested.clear();
            finishedNotified.clear();
        });
    }

    /** 等所有排队命令跑完（退出前/测试用） */
    public void awaitIdle(long timeoutMs) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            commandQueue.execute(latch::countDown);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ------------------------------------------------------------ 速度调优核心

    /**
     * 构建一份「面向速度」的会话配置。
     *
     * <p><b>为什么只用了少量 SettingsPack 方法而不是几十个调参：</b>
     * libtorrent 的默认值本来就是按「跑得快」调的 —— DHT、LSD、UPnP、NAT-PMP、uTP、
     * 协议加密、异步磁盘 I/O 全都是打开的，队列也是不限的。真正需要动的只有下面几项，
     * 而它们恰好都有 {@link SettingsPack} 的官方包装方法，不依赖任何 swig 枚举常量，
     * 因此跨版本不会编译失败也不会静默失效。
     *
     * <p>唯一需要额外说明的是 <b>上传限速</b>：BT 是互惠协议，压上传会让对方降低你的
     * 下载优先级，所以这里默认 0（不限）。
     */
    public static SettingsPack buildOptimizedSettings() {
        SettingsPack sp = new SettingsPack();

        // ---- 找节点：磁力冷启动的关键 ----
        sp.setEnableDht(true);
        sp.setEnableLsd(true);
        try {
            sp.setDhtBootstrapNodes(DHT_BOOTSTRAP);
        } catch (Throwable ignored) {
        }
        sp.stopTrackerTimeout(3);

        // ---- 连接数：手机 BT 慢的头号原因就是这里太小 ----
        sp.connectionsLimit(800);
        sp.maxPeerlistSize(8000);
        sp.activeDownloads(-1);     // 全部任务同时下，不进队列
        sp.activeSeeds(-1);
        sp.activeLimit(-1);
        sp.activeChecking(1);

        // ---- I/O 缓冲：高速下别让磁盘卡住下载 ----
        sp.maxQueuedDiskBytes(32 * 1024 * 1024);
        sp.sendBufferWatermark(3 * 1024 * 1024);

        // ---- 杂项 ----
        sp.alertQueueSize(2000);

        // ---- 显式写清楚：默认就是不限速 ----
        sp.downloadRateLimit(0);
        sp.uploadRateLimit(0);

        return sp;
    }

    private static int[] defaultAlertMask() {
        // 只订阅需要的告警；返回 null 表示「全部」，那样 JNI 回调会多很多。
        return new int[]{
                AlertType.ADD_TORRENT.swig(),
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.TORRENT_FINISHED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_ERROR.swig(),
                AlertType.SAVE_RESUME_DATA.swig(),
                AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                AlertType.TORRENT_CHECKED.swig(),
                AlertType.STATE_CHANGED.swig(),
                AlertType.TORRENT_DELETED.swig(),
                AlertType.LSD_ERROR.swig(),
                AlertType.PORTMAP_ERROR.swig()
        };
    }

    // ----------------------------------------------------------------- 告警处理

    private AlertListener buildAlertListener() {
        return new AlertListener() {
            @Override
            public int[] types() {
                return defaultAlertMask();
            }

            @Override
            public void alert(Alert<?> alert) {
                try {
                    dispatchAlert(alert);
                } catch (Throwable t) {
                    Log.w(TAG, "alert dispatch failed: " + alert.type(), t);
                }
            }
        };
    }

    private void dispatchAlert(Alert<?> alert) {
        switch (alert.type()) {
            case ADD_TORRENT: {
                TorrentHandle h = ((AddTorrentAlert) alert).handle();
                if (h != null) {
                    resumeRequested.remove(h.infoHash());
                }
                break;
            }
            case METADATA_RECEIVED: {
                MetadataReceivedAlert a = (MetadataReceivedAlert) alert;
                TorrentHandle h = a.handle();
                if (h == null) {
                    break;
                }
                TorrentInfo info = h.torrentFile();
                if (info != null) {
                    knownNames.put(h.infoHash(), info.name());
                }
                Listener l = listener;
                if (l != null && info != null) {
                    l.onMetadataReceived(h, info);
                } else if (l != null) {
                    l.onStatusDirty();
                }
                break;
            }
            case TORRENT_FINISHED: {
                TorrentHandle h = ((TorrentFinishedAlert) alert).handle();
                if (h != null && finishedNotified.add(h.infoHash())) {
                    Listener l = listener;
                    if (l != null) {
                        l.onTorrentFinished(h);
                    }
                }
                break;
            }
            case TORRENT_ERROR: {
                TorrentErrorAlert a = (TorrentErrorAlert) alert;
                rememberError(a.handle(), a.message());
                break;
            }
            case FILE_ERROR: {
                FileErrorAlert a = (FileErrorAlert) alert;
                // 文件错误通常是「磁盘满 / 没有写权限」，必须让用户看见
                rememberError(a.handle(), a.message());
                break;
            }
            case SAVE_RESUME_DATA: {
                SaveResumeDataAlert a = (SaveResumeDataAlert) alert;
                TorrentHandle h = a.handle();
                if (h == null) {
                    break;
                }
                byte[] data = extractResumeBytes(a);
                if (data != null && data.length > 0) {
                    resumeRequested.remove(h.infoHash());
                    BiConsumer<TorrentHandle, byte[]> cb = resumeCallbacks.remove(h.infoHash());
                    if (cb != null) {
                        cb.accept(h, data);
                    }
                    Listener l = listener;
                    if (l != null) {
                        l.onResumeDataReady(h, data);
                    }
                }
                break;
            }
            case SAVE_RESUME_DATA_FAILED: {
                SaveResumeDataFailedAlert a = (SaveResumeDataFailedAlert) alert;
                TorrentHandle h = a.handle();
                if (h != null) {
                    resumeRequested.remove(h.infoHash());
                    resumeCallbacks.remove(h.infoHash());
                }
                break;
            }
            case STATE_CHANGED:
            case TORRENT_DELETED: {
                Listener l = listener;
                if (l != null) {
                    l.onStatusDirty();
                }
                break;
            }
            case LSD_ERROR:
            case PORTMAP_ERROR:
                // 路由器不支持 UPnP 之类的环境问题，不打扰用户
                break;
            default:
                break;
        }
    }

    private void rememberError(TorrentHandle h, String msg) {
        if (h == null || msg == null || msg.isEmpty()) {
            return;
        }
        errorMessages.put(h.infoHash(), msg);
        Listener l = listener;
        if (l != null) {
            l.onStatusDirty();
        }
    }

    /**
     * 从 SAVE_RESUME_DATA alert 里取出 bencode 字节。
     *
     * <p>取值链路：alert → native {@code add_torrent_params} →
     * {@link AddTorrentParams#writeResumeDataBuf} 序列化成字节。
     * 全程只用 javadoc 里确认存在的 API；任何一步失败就返回 null
     * （调用方会退化成「下次重新校验」，功能不受影响）。
     */
    private static byte[] extractResumeBytes(SaveResumeDataAlert alert) {
        try {
            org.libtorrent4j.swig.add_torrent_params nativeParams = alert.resumeData();
            if (nativeParams == null) {
                return null;
            }
            byte[] data = AddTorrentParams.writeResumeDataBuf(new AddTorrentParams(nativeParams));
            return (data != null && data.length > 0) ? data : null;
        } catch (Throwable t) {
            Log.w(TAG, "extract resume data failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------- 任务操作

    /**
     * 从磁力链接添加任务。
     *
     * @param magnet           magnet:?xt=... 或纯 info-hash
     * @param saveDir          保存目录（必须可写）
     * @param sequential       是否顺序下载（边下边播）
     * @param startImmediately 是否立刻开始
     * @return 成功返回 infoHash 的十六进制串，失败返回 null
     */
    public String addMagnet(String magnet, File saveDir, boolean sequential,
                            boolean startImmediately) {
        final String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        whenReady(() -> {
            try {
                AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet.trim());
                p.setSavePath(saveDir.getAbsolutePath());
                p.setStorageMode(StorageMode.STORAGE_MODE_SPARSE);
                applyFlags(p, sequential, startImmediately);
                withExtraTrackers(p);

                InfoHash ih = p.getInfoHashes();
                Sha1Hash sha1 = resolveHash(ih);
                if (sha1 == null) {
                    Log.e(TAG, "magnet has no usable info-hash: " + magnet);
                    result[0] = null;
                    return;
                }
                if (session.find(sha1) == null) {
                    session.asyncAddTorrent(p);
                }
                result[0] = sha1.toHex();
            } catch (Throwable t) {
                Log.e(TAG, "addMagnet failed: " + magnet, t);
                result[0] = null;
            } finally {
                latch.countDown();
            }
        });
        await(latch);
        return result[0];
    }

    /**
     * 从 .torrent 文件内容添加任务。
     *
     * @param torrentBytes .torrent 原始字节
     */
    public String addTorrentFile(byte[] torrentBytes, File saveDir, boolean sequential,
                                 boolean startImmediately) {
        final String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        whenReady(() -> {
            try {
                TorrentInfo info = new TorrentInfo(torrentBytes);
                AddTorrentParams p = new AddTorrentParams();
                p.setTorrentInfo(info);
                p.setSavePath(saveDir.getAbsolutePath());
                p.setStorageMode(StorageMode.STORAGE_MODE_SPARSE);
                applyFlags(p, sequential, startImmediately);
                withExtraTrackers(p);

                Sha1Hash sha1 = info.infoHash();
                if (session.find(sha1) == null) {
                    session.asyncAddTorrent(p);
                }
                result[0] = sha1.toHex();
            } catch (Throwable t) {
                Log.e(TAG, "addTorrentFile failed", t);
                result[0] = null;
            } finally {
                latch.countDown();
            }
        });
        await(latch);
        return result[0];
    }

    /**
     * 用磁力链接把任务重新挂回会话（进程重启后的恢复路径）。
     *
     * <p><b>为什么不用 resume data 走"快速恢复"：</b>
     * libtorrent4j 2.1.0-39 的 Java 层只给了「把 resume data 写出去」的 API
     * （{@link AddTorrentParams#writeResumeDataBuf}），**没有**对应的读入 API
     * （底层的 {@code add_torrent_params} 没有暴露 {@code read_resume_data}）。
     * 所以这里走的是官方支持的等价路径：用原磁力链接重新添加，
     * libtorrent 会拿磁盘上已有的分片做一次校验，校验过的部分<b>不会重下</b>。
     *
     * <p>代价是启动时多一次哈希校验（每 GB 大约几秒到十几秒），
     * 换来的是：不会因为 resume data 与磁盘不一致而静默出错。
     * 下载数据本身是安全的。
     *
     * @param infoHashHex v1 info-hash
     * @param magnet      原始磁力链接；为空则无法恢复
     * @param saveDir     保存目录
     * @return 是否成功挂回会话
     */
    public boolean restore(String infoHashHex, String magnet, java.io.File saveDir) {
        if (magnet == null || magnet.trim().isEmpty()) {
            Log.w(TAG, "restore skipped: no magnet for " + infoHashHex);
            return false;
        }
        return addMagnet(magnet, saveDir, false, false) != null;
    }

    private void applyFlags(AddTorrentParams p, boolean sequential, boolean startImmediately) {
        try {
            org.libtorrent4j.swig.torrent_flags_t flags = TorrentFlags.AUTO_MANAGED;
            if (!startImmediately) {
                flags = flags.or_(TorrentFlags.PAUSED);
            }
            if (sequential) {
                flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            }
            p.setFlags(flags);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 InfoHash 里取一个可用的 20 字节哈希。
     * 纯 v2 torrent 没有 v1 哈希，此时 getBest() 会返回截断的 v2 哈希 —— 对
     * 「能连上同一批 peer 并完成下载」来说已经够用。
     */
    private static Sha1Hash resolveHash(InfoHash ih) {
        if (ih == null) {
            return null;
        }
        try {
            if (ih.hasV1()) {
                return ih.getV1();
            }
        } catch (Throwable ignored) {
        }
        try {
            return ih.getBest();
        } catch (Throwable t) {
            return null;
        }
    }

    private void withExtraTrackers(AddTorrentParams p) {
        try {
            List<String> trackers = new ArrayList<>();
            List<String> existing = p.getTrackers();
            if (existing != null) {
                trackers.addAll(existing);
            }
            for (String t : EXTRA_TRACKERS) {
                if (!trackers.contains(t)) {
                    trackers.add(t);
                }
            }
            p.setTrackers(trackers);
        } catch (Throwable ignored) {
        }
    }

    public void pause(String infoHashHex) {
        withHandle(infoHashHex, h -> {
            h.unsetFlags(TorrentFlags.AUTO_MANAGED);
            h.pause();
        });
    }

    public void resume(String infoHashHex) {
        withHandle(infoHashHex, h -> {
            errorMessages.remove(h.infoHash());
            h.setFlags(TorrentFlags.AUTO_MANAGED);
            h.resume();
        });
    }

    /** 删除任务；removeFiles 为 true 时连数据一起删 */
    public void remove(String infoHashHex, boolean removeFiles) {
        whenReady(() -> {
            TorrentHandle h = find(infoHashHex);
            if (h == null) {
                return;
            }
            Sha1Hash ih = h.infoHash();
            try {
                if (removeFiles) {
                    h.unsetFlags(TorrentFlags.AUTO_MANAGED);
                }
                session.remove(h, removeFiles ? SessionManager.DELETE_FILES : 0);
            } catch (Throwable t) {
                Log.w(TAG, "remove failed", t);
            } finally {
                knownNames.remove(ih);
                errorMessages.remove(ih);
                finishedNotified.remove(ih);
                resumeCallbacks.remove(ih);
                resumeRequested.remove(ih);
            }
        });
    }

    /** 强制重新校验已有数据 */
    public void forceRecheck(String infoHashHex) {
        withHandle(infoHashHex, TorrentHandle::forceRecheck);
    }

    /** 主动请求一份 resume data（定时保存断点用） */
    public void requestResumeData(String infoHashHex,
                                 BiConsumer<TorrentHandle, byte[]> callback) {
        whenReady(() -> {
            TorrentHandle h = find(infoHashHex);
            if (h == null) {
                return;
            }
            if (!resumeRequested.add(h.infoHash())) {
                return;     // 已经有一次请求在飞
            }
            if (callback != null) {
                resumeCallbacks.put(h.infoHash(), callback);
            }
            try {
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
            } catch (Throwable t) {
                resumeRequested.remove(h.infoHash());
                resumeCallbacks.remove(h.infoHash());
            }
        });
    }

    /** 顺序下载开关（对正在跑的任务也生效） */
    public void setSequential(String infoHashHex, boolean sequential) {
        withHandle(infoHashHex, h -> {
            if (sequential) {
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            } else {
                h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            }
        });
    }

    /**
     * 设置每个文件的下载优先级。
     *
     * @param priorities 取值 0~7，0 = 不下载，4 = 默认
     */
    public void setFilePriorities(String infoHashHex, int[] priorities) {
        withHandle(infoHashHex, h -> {
            TorrentInfo info = h.torrentFile();
            if (info == null) {
                return;
            }
            int n = info.numFiles();
            Priority[] ps = new Priority[n];
            for (int i = 0; i < n; i++) {
                int want = (i < priorities.length) ? priorities[i] : 4;
                ps[i] = toPriority(want);
            }
            h.prioritizeFiles(ps);
        });
    }

    private static Priority toPriority(int level) {
        switch (level) {
            case 0: return Priority.IGNORE;
            case 1: return Priority.LOW;
            case 2: return Priority.TWO;
            case 3: return Priority.THREE;
            case 5: return Priority.FIVE;
            case 6: return Priority.SIX;
            case 7: return Priority.TOP_PRIORITY;
            default: return Priority.DEFAULT;
        }
    }

    /** 会话级全局限速，0 或负数表示不限 */
    public void setDownloadLimitKbps(int kbps) {
        whenReady(() -> {
            try {
                session.downloadRateLimit(kbps <= 0 ? 0 : kbps * 1024);
            } catch (Throwable ignored) {
            }
        });
    }

    public void setUploadLimitKbps(int kbps) {
        whenReady(() -> {
            try {
                session.uploadRateLimit(kbps <= 0 ? 0 : kbps * 1024);
            } catch (Throwable ignored) {
            }
        });
    }

    // ------------------------------------------------------------------ 查询

    /** 单个任务的实时状态；任务不存在返回 null */
    public TaskStatus statusOf(String infoHashHex) {
        TorrentHandle h = find(infoHashHex);
        return h == null ? null : buildStatus(h);
    }

    /** 所有任务的实时状态，key 为 infoHash 十六进制串 */
    public Map<String, TaskStatus> allStatuses() {
        Map<String, TaskStatus> out = new HashMap<>();
        for (TorrentHandle h : safeHandles()) {
            try {
                out.put(h.infoHash().toHex(), buildStatus(h));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 当前会话的总下载速度（字节/秒） */
    public long sessionDownloadRate() {
        try {
            return session == null ? 0L : session.downloadRate();
        } catch (Throwable t) {
            return 0L;
        }
    }

    public long sessionUploadRate() {
        try {
            return session == null ? 0L : session.uploadRate();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** DHT 已连上的节点数，可用于判断磁力为何找不到人 */
    public long dhtNodes() {
        try {
            return session == null ? 0L : session.dhtNodes();
        } catch (Throwable t) {
            return 0L;
        }
    }

    public String nameOf(String infoHashHex) {
        TorrentHandle h = find(infoHashHex);
        if (h == null) {
            return null;
        }
        TorrentInfo info = h.torrentFile();
        if (info != null) {
            String n = info.name();
            if (n != null && !n.isEmpty()) {
                knownNames.put(h.infoHash(), n);
                return n;
            }
        }
        String cached = knownNames.get(h.infoHash());
        return cached;
    }

    public TorrentInfo infoOf(String infoHashHex) {
        TorrentHandle h = find(infoHashHex);
        return h == null ? null : h.torrentFile();
    }

    /** 每个文件已下载的字节数，下标对应 .torrent 里的顺序 */
    public long[] fileProgressOf(String infoHashHex) {
        TorrentHandle h = find(infoHashHex);
        if (h == null) {
            return null;
        }
        try {
            return h.fileProgress();
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean exists(String infoHashHex) {
        return find(infoHashHex) != null;
    }

    public String makeMagnet(String infoHashHex) {
        TorrentHandle h = find(infoHashHex);
        if (h == null) {
            return null;
        }
        try {
            return h.makeMagnetUri();
        } catch (Throwable t) {
            return null;
        }
    }

    private List<TorrentHandle> safeHandles() {
        try {
            if (session == null) {
                return Collections.emptyList();
            }
            List<TorrentHandle> list = session.getTorrentHandles();
            return list == null ? Collections.emptyList() : list;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private TorrentHandle find(String infoHashHex) {
        if (session == null || infoHashHex == null || infoHashHex.length() < 40) {
            return null;
        }
        try {
            return session.find(Sha1Hash.parseHex(infoHashHex));
        } catch (Throwable t) {
            return null;
        }
    }

    private interface HandleAction {
        void run(TorrentHandle h);
    }

    private void withHandle(String infoHashHex, HandleAction action) {
        whenReady(() -> {
            TorrentHandle h = find(infoHashHex);
            if (h == null) {
                return;
            }
            try {
                action.run(h);
            } catch (Throwable t) {
                Log.w(TAG, "handle action failed", t);
            }
        });
    }

    private TaskStatus buildStatus(TorrentHandle h) {
        TorrentStatus st = h.status();
        Sha1Hash ih = h.infoHash();

        TorrentInfo info = h.torrentFile();
        String name = null;
        long total = -1L;
        int fileCount = 0;
        if (info != null) {
            name = info.name();
            total = info.totalSize();
            fileCount = info.numFiles();
            if (name != null && !name.isEmpty()) {
                knownNames.put(ih, name);
            }
        }
        if (name == null || name.isEmpty()) {
            name = knownNames.get(ih);
        }

        TaskStatus.State state = TaskStatus.mapTorrentState(st.state());
        if (state == TaskStatus.State.PAUSED && st.isFinished()) {
            state = TaskStatus.State.FINISHED;
        }
        if (state != TaskStatus.State.FINISHED && st.isFinished() && st.isSeeding()) {
            state = TaskStatus.State.SEEDING;
        }

        String err = errorMessages.get(ih);
        if (err != null && !err.isEmpty()
                && state != TaskStatus.State.DOWNLOADING
                && state != TaskStatus.State.SEEDING
                && state != TaskStatus.State.FINISHED) {
            state = TaskStatus.State.ERROR;
        }

        boolean sequential = false;
        try {
            sequential = st.flags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).nonZero();
        } catch (Throwable ignored) {
        }

        return new TaskStatus(
                state,
                name,
                st.progress(),
                st.downloadPayloadRate(),
                st.uploadPayloadRate(),
                st.totalDone(),
                st.totalUpload(),
                total,
                st.numPeers(),
                st.numSeeds(),
                fileCount,
                err,
                sequential,
                st.isSeeding()
        );
    }

    // ------------------------------------------------------------------ 杂项

    private void notifyEngineError(String msg) {
        Listener l = listener;
        if (l != null) {
            l.onEngineError(msg);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把字节数变成人类可读的字符串 */
    public static String human(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** 速度显示，固定 KB/s 或 MB/s */
    public static String speed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 KB/s";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB/s", kb);
        }
        return String.format(Locale.US, "%.2f MB/s", kb / 1024.0);
    }
}
