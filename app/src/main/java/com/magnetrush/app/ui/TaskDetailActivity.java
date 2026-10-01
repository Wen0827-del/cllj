package com.magnetrush.app.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.magnetrush.app.R;
import com.magnetrush.app.core.TaskStatus;
import com.magnetrush.app.core.TaskStore;
import com.magnetrush.app.core.TorrentEngine;
import com.magnetrush.app.core.TorrentTask;
import com.magnetrush.app.service.DownloadService;
import com.magnetrush.app.service.TorrentView;
import com.magnetrush.app.util.Fmt;

import org.libtorrent4j.FileStorage;
import org.libtorrent4j.TorrentInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情：进度、速度、文件级选择、暂停/删除。
 *
 * <p>它同时从两个地方取数据：
 * <ul>
 *   <li>服务广播的 {@link TorrentView} —— 实时速度 / 进度 / 节点数；</li>
 *   <li>直接问 {@link TorrentEngine} 要 {@link TorrentInfo} —— 文件列表和总大小，
 *       这些是静态信息，不需要每秒更新。</li>
 * </ul>
 */
public class TaskDetailActivity extends AppCompatActivity {

    public static final String EXTRA_HASH = "hash";
    public static final String EXTRA_NAME = "name";

    private String hash;
    private TaskStore store;

    private TextView nameView;
    private TextView statusView;
    private TextView savePathView;
    private TextView filesEmpty;
    private LinearProgressIndicator progress;
    private MaterialButton toggleButton;
    private MaterialSwitch sequentialSwitch;
    private RecyclerView fileList;
    private FileAdapter fileAdapter;

    private boolean receiverRegistered;
    private boolean fileListLoaded = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(DownloadService.EXTRA_TASKS_JSON);
            TorrentView v = findView(json);
            if (v != null) {
                render(v);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        store = TaskStore.get(this);
        hash = getIntent().getStringExtra(EXTRA_HASH);
        if (hash == null) {
            finish();
            return;
        }
        hash = hash.toLowerCase();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        nameView = findViewById(R.id.detail_name);
        statusView = findViewById(R.id.detail_status);
        savePathView = findViewById(R.id.detail_save_path);
        filesEmpty = findViewById(R.id.detail_files_empty);
        progress = findViewById(R.id.detail_progress);
        toggleButton = findViewById(R.id.btn_toggle);
        sequentialSwitch = findViewById(R.id.switch_sequential);
        fileList = findViewById(R.id.file_list);

        MaterialButton recheck = findViewById(R.id.btn_recheck);
        MaterialButton share = findViewById(R.id.btn_share);
        MaterialButton delete = findViewById(R.id.btn_delete);

        fileAdapter = new FileAdapter((index, wanted) -> applyFilePriorities());
        fileList.setLayoutManager(new LinearLayoutManager(this));
        fileList.setAdapter(fileAdapter);

        TorrentTask task = store.get(hash);
        String title = getIntent().getStringExtra(EXTRA_NAME);
        nameView.setText(task != null && task.name != null ? task.name
                : (title != null ? title : hash));
        if (task != null) {
            savePathView.setText(task.savePath != null ? task.savePath : "--");
            sequentialSwitch.setChecked(task.sequential);
        }

        toggleButton.setOnClickListener(v -> {
            TorrentView v2 = lastView;
            if (v2 != null && v2.isRunning()) {
                DownloadService.pauseTask(this, hash);
            } else {
                DownloadService.resumeTask(this, hash);
            }
        });

        recheck.setOnClickListener(v -> {
            TorrentEngine.get().forceRecheck(hash);
            toast(getString(R.string.toast_recheck_started));
        });

        share.setOnClickListener(v -> copyMagnet());

        delete.setOnClickListener(v -> confirmDelete());

        sequentialSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TorrentEngine.get().setSequential(hash, isChecked);
            TorrentTask t = store.get(hash);
            if (t != null) {
                t.sequential = isChecked;
                store.put(t);
            }
        });

        loadFileList();
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
        DownloadService.start(this);
        // 文件列表可能需要等元数据，这里再试一次
        if (!fileListLoaded) {
            fileList.postDelayed(this::loadFileList, 1500);
        }
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

    // ================================================================ 渲染

    private TorrentView lastView;

    @Nullable
    private TorrentView findView(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (hash.equalsIgnoreCase(o.optString("infoHash", ""))) {
                    return TorrentView.fromJson(o);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void render(TorrentView v) {
        lastView = v;

        if (v.name != null && !v.name.isEmpty()) {
            nameView.setText(v.name);
        }
        if (v.savePath != null && !v.savePath.isEmpty()) {
            savePathView.setText(v.savePath);
        }

        float pct = Math.max(0f, Math.min(1f, v.progress));
        if (v.totalBytes <= 0 && v.isRunning()) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(false);
            progress.setProgress((int) (pct * 1000f));
        }

        StringBuilder sb = new StringBuilder();
        if (v.totalBytes > 0) {
            sb.append(Fmt.size(v.downloadedBytes)).append(" / ").append(Fmt.size(v.totalBytes))
                    .append("  ·  ").append(Fmt.percent(v.progress));
        } else {
            sb.append("已下载 ").append(Fmt.size(v.downloadedBytes));
        }
        sb.append('\n').append("↓ ").append(Fmt.speed(v.downloadRate))
                .append("   ↑ ").append(Fmt.speed(v.uploadRate));
        sb.append('\n').append(v.peers).append(" 个已连接节点");
        if (v.seeds > 0) {
            sb.append("（含 ").append(v.seeds).append(" 个种子）");
        }
        long remain = v.remainBytes();
        if (remain > 0 && v.downloadRate > 0) {
            sb.append("  ·  剩余约 ").append(Fmt.eta(remain, v.downloadRate));
        }

        TaskStatus.State s = v.stateEnum();
        if (s == TaskStatus.State.PAUSED) {
            sb.append("\n状态：已暂停");
        } else if (s == TaskStatus.State.CHECKING) {
            sb.append("\n状态：正在校验磁盘上的已有数据");
        } else if (s == TaskStatus.State.FETCHING_METADATA) {
            sb.append("\n状态：正在获取元数据（还没有文件列表）");
        } else if (s == TaskStatus.State.ERROR) {
            sb.append("\n错误：").append(v.error != null ? v.error : "未知");
        } else if (v.completed) {
            sb.append("\n完成于 ").append(Fmt.ago(v.completedAt));
        }
        statusView.setText(sb.toString());

        toggleButton.setText(v.isRunning() ? R.string.action_pause : R.string.action_resume);

        if (!fileListLoaded && v.fileCount > 0) {
            loadFileList();
        }
    }

    // ================================================================ 文件列表

    private void loadFileList() {
        TorrentInfo info = TorrentEngine.get().infoOf(hash);
        if (info == null) {
            filesEmpty.setVisibility(View.VISIBLE);
            return;
        }
        try {
            FileStorage fs = info.files();
            int n = info.numFiles();
            long[] done = TorrentEngine.get().fileProgressOf(hash);
            int[] wanted = currentPriorities(n);

            List<FileAdapter.FileItem> items = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String path = fs.filePath(i);
                long size = fs.fileSize(i);
                long d = (done != null && i < done.length) ? done[i] : 0L;
                items.add(new FileAdapter.FileItem(path, size, d, wanted[i] != 0));
            }
            fileAdapter.submit(items);
            filesEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
            fileListLoaded = n > 0;
        } catch (Throwable e) {
            filesEmpty.setVisibility(View.VISIBLE);
        }
    }

    /** 已保存的优先级；没保存过就默认全选 */
    private int[] currentPriorities(int count) {
        TorrentTask t = store.get(hash);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            if (t != null && t.filePriorities != null && i < t.filePriorities.length) {
                out[i] = t.filePriorities[i];
            } else {
                out[i] = 4;
            }
        }
        return out;
    }

    private void applyFilePriorities() {
        int[] priorities = fileAdapter.snapshot();
        TorrentEngine.get().setFilePriorities(hash, priorities);
        TorrentTask t = store.get(hash);
        if (t != null) {
            t.filePriorities = priorities;
            store.put(t);
        }
        Toast.makeText(this, R.string.toast_priorities_applied, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ 操作

    private void copyMagnet() {
        String magnet = TorrentEngine.get().makeMagnet(hash);
        if (magnet == null) {
            TorrentTask t = store.get(hash);
            magnet = t != null ? t.magnet : null;
        }
        if (magnet == null || magnet.isEmpty()) {
            magnet = "magnet:?xt=urn:btih:" + hash;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magnet", magnet));
                toast(getString(R.string.toast_magnet_copied));
            }
        } catch (Throwable e) {
            toast(getString(R.string.toast_copy_failed));
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete_with_files, (d, w) -> {
                    DownloadService.cancelTaskWithFiles(this, hash);
                    toast(getString(R.string.toast_deleted));
                    finish();
                })
                .setNeutralButton(R.string.delete_task_only, (d, w) -> {
                    DownloadService.cancelTaskKeepFiles(this, hash);
                    toast(getString(R.string.toast_task_removed));
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 供设置页复用（当前详情页没有直接入口） */
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
