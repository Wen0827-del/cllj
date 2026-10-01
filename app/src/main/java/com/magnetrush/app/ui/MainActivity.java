package com.magnetrush.app.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.magnetrush.app.App;
import com.magnetrush.app.R;
import com.magnetrush.app.core.TaskStatus;
import com.magnetrush.app.service.DownloadService;
import com.magnetrush.app.service.TorrentView;
import com.magnetrush.app.util.Fmt;
import com.magnetrush.app.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：任务列表 + 添加磁力链接。
 *
 * <p>它不直接碰 libtorrent —— 所有操作都是给 {@link DownloadService} 发 Intent，
 * 状态则通过 {@link DownloadService#BROADCAST_STATE} 广播拿回来。
 */
public class MainActivity extends AppCompatActivity implements TaskAdapter.RowListener {

    private MaterialToolbar toolbar;
    private RecyclerView list;
    private View emptyView;
    private View summaryBar;
    private TextView summaryText;
    private TaskAdapter adapter;

    private Settings settings;

    /** 已选中的 .torrent 文件内容（内存里放着，添加完就丢） */
    private byte[] pickedTorrentBytes;
    private String pickedTorrentName;

    private AlertDialog addDialog;
    private TextInputEditText magnetInput;
    private TextView saveDirText;
    private TextView pickedFileText;
    private MaterialSwitch sequentialSwitch;

    private boolean receiverRegistered = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(DownloadService.EXTRA_TASKS_JSON);
            long down = intent.getLongExtra(DownloadService.EXTRA_SESSION_DOWN, 0L);
            long up = intent.getLongExtra(DownloadService.EXTRA_SESSION_UP, 0L);
            long dht = intent.getLongExtra(DownloadService.EXTRA_DHT_NODES, 0L);
            String engineError = intent.getStringExtra(DownloadService.EXTRA_ENGINE_ERROR);
            render(json, down, up, dht, engineError);
        }
    };

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // 用户拒绝也无所谓，只是不显示进度通知
            });

    private final ActivityResultLauncher<String[]> torrentFilePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                loadTorrentFile(uri);
            });

    // ================================================================ 生命周期

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = Settings.get(this);

        toolbar = findViewById(R.id.toolbar);
        list = findViewById(R.id.task_list);
        emptyView = findViewById(R.id.empty_view);
        summaryBar = findViewById(R.id.summary_bar);
        summaryText = findViewById(R.id.summary_text);
        FloatingActionButton fab = findViewById(R.id.fab_add);

        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenu);

        adapter = new TaskAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        fab.setOnClickListener(v -> showAddDialog());

        askNotificationPermission();
        warnIfNativeMissing();

        // 先确保服务在跑，再处理外部传入的磁力链接
        DownloadService.start(this);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(DownloadService.BROADCAST_STATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        }
        // 立刻问一次服务当前状态，避免广播间隔内界面是空的
        DownloadService.start(this);
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    // ================================================================ 菜单

    private boolean onToolbarMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_resume_all) {
            sendServiceAction(DownloadService.ACTION_RESUME_ALL, null);
            return true;
        }
        if (id == R.id.action_pause_all) {
            sendServiceAction(DownloadService.ACTION_PAUSE_ALL, null);
            return true;
        }
        return false;
    }

    private void sendServiceAction(String action, String hash) {
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(action);
        if (hash != null) {
            i.putExtra(DownloadService.EXTRA_HASH, hash);
        }
        DownloadService.dispatch(this, i);
    }

    // ================================================================ 外部进入

    /** 处理浏览器「打开磁力链接」、分享文本、打开 .torrent 文件等入口 */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Uri data = intent.getData();

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String scheme = data.getScheme();
            if ("magnet".equalsIgnoreCase(scheme)) {
                DownloadService.addMagnet(this, data.toString(), settings.sequentialDefault());
                toast(getString(R.string.toast_added_from_link));
                return;
            }
            // content:// 或 file:// 的 .torrent
            loadTorrentFileAndAdd(data);
            return;
        }

        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(text) && text.trim().toLowerCase(Locale.US).startsWith("magnet:")) {
                DownloadService.addMagnet(this, extractMagnet(text), settings.sequentialDefault());
                toast(getString(R.string.toast_added_from_link));
                return;
            }
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                loadTorrentFileAndAdd(stream);
            }
        }
    }

    /** 从一段可能夹着说明文字的分享内容里把 magnet: 那一段抠出来 */
    private static String extractMagnet(String raw) {
        String s = raw.trim();
        int idx = s.toLowerCase(Locale.US).indexOf("magnet:?");
        if (idx < 0) {
            return s;
        }
        String sub = s.substring(idx);
        int space = sub.indexOf(' ');
        int newline = sub.indexOf('\n');
        int cut = sub.length();
        if (space > 0) {
            cut = Math.min(cut, space);
        }
        if (newline > 0) {
            cut = Math.min(cut, newline);
        }
        return sub.substring(0, cut);
    }

    private void loadTorrentFileAndAdd(Uri uri) {
        byte[] bytes = readAllBytes(uri);
        if (bytes == null) {
            toast(getString(R.string.toast_file_read_failed));
            return;
        }
        addTorrentBytes(bytes, uri.getLastPathSegment());
    }

    private void addTorrentBytes(byte[] bytes, String name) {
        // 服务只接受磁力链接或文件路径；这里落一个临时文件再让它读取
        try {
            java.io.File tmp = new java.io.File(getCacheDir(), "incoming.torrent");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(bytes);
            }
            DownloadService.dispatch(this, DownloadService.buildAddTorrentIntent(this, tmp.getAbsolutePath(),
                    settings.sequentialDefault()));
            toast(getString(R.string.toast_added_torrent) + (name != null ? name : ""));
        } catch (Throwable e) {
            toast(getString(R.string.toast_file_read_failed));
        }
    }

    @Nullable
    private byte[] readAllBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ 添加对话框

    private void showAddDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_add_task, null, false);
        magnetInput = content.findViewById(R.id.magnet_input);
        saveDirText = content.findViewById(R.id.save_dir_text);
        pickedFileText = content.findViewById(R.id.picked_file);
        sequentialSwitch = content.findViewById(R.id.switch_sequential);
        MaterialButton pickButton = content.findViewById(R.id.btn_pick_file);

        sequentialSwitch.setChecked(settings.sequentialDefault());
        saveDirText.setText(getString(R.string.add_save_to_value, settings.saveDir(this).getAbsolutePath()));
        updatePickedFileLabel();

        pickButton.setOnClickListener(v ->
                torrentFilePicker.launch(new String[]{"application/x-bittorrent", "*/*"}));

        // 剪贴板里如果有磁力链接，直接预填，省一次粘贴
        prefillFromClipboard();

        addDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_title)
                .setView(content)
                .setPositiveButton(R.string.action_start, null)   // 先不绑定，避免校验失败自动关闭
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        addDialog.setOnShowListener(d -> addDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> onConfirmAdd()));
        addDialog.show();
    }

    private void prefillFromClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text != null && text.toString().trim().toLowerCase(Locale.US).startsWith("magnet:")) {
                magnetInput.setText(text.toString().trim());
            }
        } catch (Throwable ignored) {
        }
    }

    private void updatePickedFileLabel() {
        if (pickedTorrentName != null) {
            pickedFileText.setVisibility(View.VISIBLE);
            pickedFileText.setText(getString(R.string.add_picked_file, pickedTorrentName));
        } else {
            pickedFileText.setVisibility(View.GONE);
        }
    }

    private void loadTorrentFile(Uri uri) {
        byte[] bytes = readAllBytes(uri);
        if (bytes == null || bytes.length == 0) {
            toast(getString(R.string.toast_file_read_failed));
            return;
        }
        pickedTorrentBytes = bytes;
        pickedTorrentName = uri.getLastPathSegment();
        if (pickedTorrentName == null) {
            pickedTorrentName = "torrent";
        }
        if (magnetInput != null) {
            updatePickedFileLabel();
        }
    }

    private void onConfirmAdd() {
        boolean sequential = sequentialSwitch != null && sequentialSwitch.isChecked();

        if (pickedTorrentBytes != null) {
            // 落临时文件交给服务
            String path;
            try {
                java.io.File tmp = new java.io.File(getCacheDir(), "picked.torrent");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(pickedTorrentBytes);
                }
                path = tmp.getAbsolutePath();
            } catch (Throwable e) {
                toast(getString(R.string.toast_file_read_failed));
                return;
            }
            DownloadService.dispatch(this,
                    DownloadService.buildAddTorrentIntent(this, path, sequential));
            resetPickedFile();
            if (addDialog != null) {
                addDialog.dismiss();
            }
            return;
        }

        String raw = magnetInput != null && magnetInput.getText() != null
                ? magnetInput.getText().toString().trim() : "";
        String magnet = normalizeMagnet(raw);
        if (magnet == null) {
            if (magnetInput != null) {
                magnetInput.setError(getString(R.string.add_error_invalid));
            }
            return;
        }
        DownloadService.addMagnet(this, magnet, sequential);
        if (addDialog != null) {
            addDialog.dismiss();
        }
    }

    /**
     * 允许用户直接粘贴 40 位 info-hash（很多站点只给这个），自动补成磁力链接。
     *
     * @return 规范化后的磁力链接；无法识别返回 null
     */
    @Nullable
    static String normalizeMagnet(String raw) {
        if (raw == null) {
            return null;
        }
        String s = extractMagnet(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("magnet:")) {
            return lower.contains("xt=") ? s : null;
        }
        // 纯 info-hash
        if (s.matches("(?i)[0-9a-f]{40}")) {
            return "magnet:?xt=urn:btih:" + s.toLowerCase(Locale.US);
        }
        if (s.matches("(?i)[0-9a-z]{32}")) {
            // Base32 形式的 v1 hash
            return "magnet:?xt=urn:btih:" + s.toUpperCase(Locale.US);
        }
        return null;
    }

    private void resetPickedFile() {
        pickedTorrentBytes = null;
        pickedTorrentName = null;
        updatePickedFileLabel();
    }

    // ================================================================ 渲染

    private void render(String json, long down, long up, long dhtNodes, String engineError) {
        List<TorrentView> views = new ArrayList<>();
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        views.add(TorrentView.fromJson(o));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        adapter.submit(views);

        boolean hasTasks = !views.isEmpty();
        emptyView.setVisibility(hasTasks ? View.GONE : View.VISIBLE);
        summaryBar.setVisibility(hasTasks ? View.VISIBLE : View.GONE);

        int active = 0;
        for (TorrentView v : views) {
            if (v.isRunning() && v.stateEnum() != TaskStatus.State.FETCHING_METADATA) {
                active++;
            }
        }
        summaryText.setText(getString(R.string.summary_format,
                Fmt.speed(down), Fmt.speed(up), dhtNodes, active));

        if (engineError != null && !engineError.isEmpty()) {
            summaryText.setText(engineError);
        }
    }

    // ================================================================ 列表交互

    @Override
    public void onRowClick(TorrentView view) {
        Intent i = new Intent(this, TaskDetailActivity.class);
        i.putExtra(TaskDetailActivity.EXTRA_HASH, view.infoHash);
        i.putExtra(TaskDetailActivity.EXTRA_NAME, view.name);
        startActivity(i);
    }

    @Override
    public void onRowAction(TorrentView view) {
        if (view.isRunning()) {
            DownloadService.pauseTask(this, view.infoHash);
        } else {
            DownloadService.resumeTask(this, view.infoHash);
        }
    }

    // ================================================================ 权限 / 提示

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void warnIfNativeMissing() {
        if (App.isNativeLoaded()) {
            return;
        }
        // 立刻再试一次（首次可能是权限/时序问题）
        if (App.loadNative()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_failed_title)
                .setMessage(getString(R.string.native_failed_msg,
                        Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown",
                        App.nativeError() != null ? App.nativeError() : ""))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
