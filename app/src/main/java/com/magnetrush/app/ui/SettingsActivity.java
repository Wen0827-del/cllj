package com.magnetrush.app.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.magnetrush.app.App;
import com.magnetrush.app.R;
import com.magnetrush.app.core.TorrentEngine;
import com.magnetrush.app.service.DownloadService;
import com.magnetrush.app.util.Settings;

/** 设置页：限速、连接数、行为开关、保存目录。 */
public class SettingsActivity extends AppCompatActivity {

    private Settings settings;

    private TextInputEditText inputDown;
    private TextInputEditText inputUp;
    private TextInputEditText inputConn;
    private MaterialSwitch switchSequential;
    private MaterialSwitch switchWifiOnly;
    private MaterialSwitch switchSeed;
    private MaterialSwitch switchNotify;
    private TextView textSaveDir;

    private final ActivityResultLauncher<Intent> dirPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) {
                    return;
                }
                android.net.Uri uri = result.getData().getData();
                // 注意：SAF 给的 content:// 不能直接当文件路径给 libtorrent，
                // 所以这里只把「可用的真实路径」记下来；SAF 树 URI 仅用于展示。
                // 想真正用 SAF 目录需要在下载完成后做一次拷贝，见 README「保存位置」一节。
                keepTreePermission(uri);
                toast(getString(R.string.settings_saf_unsupported));
            });

    private void keepTreePermission(android.net.Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = Settings.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        inputDown = findViewById(R.id.input_down_limit);
        inputUp = findViewById(R.id.input_up_limit);
        inputConn = findViewById(R.id.input_max_conn);
        switchSequential = findViewById(R.id.switch_sequential);
        switchWifiOnly = findViewById(R.id.switch_wifi_only);
        switchSeed = findViewById(R.id.switch_seed);
        switchNotify = findViewById(R.id.switch_notify);
        textSaveDir = findViewById(R.id.text_save_dir);
        MaterialButton pickDir = findViewById(R.id.btn_pick_dir);
        MaterialButton resetDir = findViewById(R.id.btn_reset_dir);
        TextView about = findViewById(R.id.text_about);

        // ---- 回填当前值 ----
        inputDown.setText(String.valueOf(settings.downloadLimitKbps()));
        inputUp.setText(String.valueOf(settings.uploadLimitKbps()));
        inputConn.setText(String.valueOf(settings.maxConnections()));
        switchSequential.setChecked(settings.sequentialDefault());
        switchWifiOnly.setChecked(settings.wifiOnly());
        switchSeed.setChecked(settings.seedAfterFinish());
        switchNotify.setChecked(settings.notifyProgress());
        refreshSaveDirText();

        about.setText(getString(R.string.settings_about,
                Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?",
                App.isNativeLoaded() ? getString(R.string.settings_engine_ok)
                        : getString(R.string.settings_engine_fail)));

        // ---- 行为开关立即生效 ----
        switchSequential.setOnCheckedChangeListener((b, v) -> settings.setSequentialDefault(v));
        switchWifiOnly.setOnCheckedChangeListener((b, v) -> settings.setWifiOnly(v));
        switchSeed.setOnCheckedChangeListener((b, v) -> settings.setSeedAfterFinish(v));
        switchNotify.setOnCheckedChangeListener((b, v) -> settings.setNotifyProgress(v));

        pickDir.setOnClickListener(v -> {
            try {
                dirPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));
            } catch (Throwable e) {
                toast(getString(R.string.settings_saf_unsupported));
            }
        });

        resetDir.setOnClickListener(v -> {
            settings.setSaveDir(null);
            refreshSaveDirText();
            toast(getString(R.string.settings_dir_reset_done));
        });
    }

    private void refreshSaveDirText() {
        textSaveDir.setText(getString(R.string.settings_current_dir,
                settings.saveDir(this).getAbsolutePath()));
    }

    /** 输入框失焦时统一保存，避免边打字边生效导致抖动 */
    @Override
    protected void onPause() {
        applyNumericSettings();
        super.onPause();
    }

    private void applyNumericSettings() {
        int down = parseOr(inputDown, settings.downloadLimitKbps());
        int up = parseOr(inputUp, settings.uploadLimitKbps());
        int conn = parseOr(inputConn, settings.maxConnections());
        int previousConn = settings.maxConnections();

        settings.setDownloadLimitKbps(down);
        settings.setUploadLimitKbps(up);
        settings.setMaxConnections(conn);

        TorrentEngine.get().setDownloadLimitKbps(down);
        TorrentEngine.get().setUploadLimitKbps(up);

        // 连接数改动需要重建 libtorrent 会话才彻底生效，这里明确告知
        if (conn != previousConn) {
            toast(getString(R.string.settings_conn_need_restart));
        }
    }

    private static int parseOr(TextInputEditText input, int fallback) {
        if (input == null || input.getText() == null) {
            return fallback;
        }
        String s = input.getText().toString().trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
