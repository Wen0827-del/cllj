package com.magnetrush.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 应用设置。全部走 SharedPreferences，改动立即生效。
 */
public final class Settings {

    private static final String PREF = "magnetrush_settings";

    private static final String K_DOWN_LIMIT = "download_limit_kbps";
    private static final String K_UP_LIMIT = "upload_limit_kbps";
    private static final String K_MAX_CONN = "max_connections";
    private static final String K_SEQUENTIAL = "sequential_default";
    private static final String K_WIFI_ONLY = "wifi_only";
    private static final String K_SEED_AFTER = "seed_after_finish";
    private static final String K_NOTIFY = "notify_progress";
    private static final String K_SAVE_DIR = "save_dir";

    /** 默认保存目录：应用专属外部目录，无需任何存储权限 */
    public static final String DEFAULT_SAVE_DIR_NAME = "MagnetRush";

    private final SharedPreferences prefs;

    private Settings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Settings get(Context ctx) {
        return new Settings(ctx);
    }

    // ------------------------------------------------------------ 保存目录

    /**
     * 下载保存目录。
     *
     * <p>默认用 App 专属外部目录 {@code Android/data/<包名>/files/MagnetRush}：
     * <ul>
     *   <li>Android 10+ 分区存储下 <b>不需要</b>任何存储权限；</li>
     *   <li>libtorrent 可以直接以文件路径读写，不需要 SAF 的中转；</li>
     *   <li>卸载 App 时数据会被一起清掉，这是刻意的 —— 免去残留垃圾。</li>
     * </ul>
     * 用户也可以在设置里改成自己选的目录。
     */
    public File saveDir(Context ctx) {
        String custom = prefs.getString(K_SAVE_DIR, null);
        if (custom != null && !custom.isEmpty()) {
            File f = new File(custom);
            if (ensureWritable(f)) {
                return f;
            }
        }
        File def = defaultSaveDir(ctx);
        ensureWritable(def);
        return def;
    }

    public void setSaveDir(String absolutePath) {
        prefs.edit().putString(K_SAVE_DIR, absolutePath).apply();
    }

    public String saveDirRaw() {
        return prefs.getString(K_SAVE_DIR, null);
    }

    public static File defaultSaveDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) {
            ext = ctx.getFilesDir();
        }
        return new File(ext, DEFAULT_SAVE_DIR_NAME);
    }

    private static boolean ensureWritable(File dir) {
        if (dir == null) {
            return false;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        return dir.isDirectory() && dir.canWrite();
    }

    // ------------------------------------------------------------ 限速

    /** 全局下载限速（KB/s），0 = 不限 */
    public int downloadLimitKbps() {
        return prefs.getInt(K_DOWN_LIMIT, 0);
    }

    public void setDownloadLimitKbps(int kbps) {
        prefs.edit().putInt(K_DOWN_LIMIT, Math.max(0, kbps)).apply();
    }

    /** 全局上传限速（KB/s），0 = 不限。默认不限，因为压上传会反噬下载速度。 */
    public int uploadLimitKbps() {
        return prefs.getInt(K_UP_LIMIT, 0);
    }

    public void setUploadLimitKbps(int kbps) {
        prefs.edit().putInt(K_UP_LIMIT, Math.max(0, kbps)).apply();
    }

    // ------------------------------------------------------------ 连接数

    /** 全局最大连接数，默认 800 */
    public int maxConnections() {
        return prefs.getInt(K_MAX_CONN, 800);
    }

    public void setMaxConnections(int n) {
        prefs.edit().putInt(K_MAX_CONN, Math.max(30, Math.min(2000, n))).apply();
    }

    // ------------------------------------------------------------ 行为开关

    /** 新任务默认是否顺序下载 */
    public boolean sequentialDefault() {
        return prefs.getBoolean(K_SEQUENTIAL, false);
    }

    public void setSequentialDefault(boolean v) {
        prefs.edit().putBoolean(K_SEQUENTIAL, v).apply();
    }

    /** 仅 Wi-Fi 下下载 */
    public boolean wifiOnly() {
        return prefs.getBoolean(K_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean v) {
        prefs.edit().putBoolean(K_WIFI_ONLY, v).apply();
    }

    /** 下载完成后是否继续做种。默认 false：省电省流量，下完即停。 */
    public boolean seedAfterFinish() {
        return prefs.getBoolean(K_SEED_AFTER, false);
    }

    public void setSeedAfterFinish(boolean v) {
        prefs.edit().putBoolean(K_SEED_AFTER, v).apply();
    }

    /** 是否显示常驻进度通知 */
    public boolean notifyProgress() {
        return prefs.getBoolean(K_NOTIFY, true);
    }

    public void setNotifyProgress(boolean v) {
        prefs.edit().putBoolean(K_NOTIFY, v).apply();
    }
}
