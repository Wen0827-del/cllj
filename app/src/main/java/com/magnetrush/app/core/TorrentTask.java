package com.magnetrush.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个下载任务的持久化实体。
 *
 * <p>它只存「用户意图 + 断点信息」，不存实时速度/进度 —— 那些每秒从 libtorrent 现取，
 * 存下来只会让数据不一致。
 */
public final class TorrentTask {

    public enum State {
        /** 排队中 / 元数据获取中 */
        QUEUED,
        /** 正在下载 */
        DOWNLOADING,
        /** 用户暂停 */
        PAUSED,
        /** 下载完成 */
        COMPLETED,
        /** 出错 */
        ERROR
    }

    /** info-hash（v1，40 位十六进制小写） */
    public String infoHash;

    /** 显示名。元数据到手前先用磁力链接里的 dn 或 info-hash 顶上 */
    public String name;

    /** 磁力链接原文。用 .torrent 添加时可能为空 */
    public String magnet;

    /**
     * 添加时用的 .torrent 原始字节（Base64 存进 JSON）。
     *
     * <p>为什么要存这个：用 .torrent 添加的任务没有磁力链接，
     * 进程重启后如果只靠 magnet 字段就恢复不了。种子文件通常只有几十 KB，
     * 存一份换来「重启后能精确恢复」，很划算。
     */
    public byte[] torrentBytes;

    /** 保存目录绝对路径 */
    public String savePath;

    /** 元数据里声明的总字节数，未知为 -1 */
    public long totalBytes = -1L;

    /** 已下载字节数（用于重启后立刻显示进度，不等重新校验） */
    public long downloadedBytes = 0L;

    /** 每个文件的优先级，0 = 不下载，4 = 默认；空数组表示「全部下载」 */
    public int[] filePriorities = new int[0];

    /** 顺序下载（边下边播） */
    public boolean sequential = false;

    public boolean startImmediately = true;

    /** 添加时间（毫秒） */
    public long addedAt = System.currentTimeMillis();

    /** 完成时间（毫秒），未完成为 0 */
    public long completedAt = 0L;

    public State state = State.QUEUED;

    /** 最近一次错误信息 */
    public String lastError;

    public TorrentTask() {
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    // ---------------------------------------------------------------- JSON

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("infoHash", infoHash);
        o.put("name", name);
        o.put("magnet", magnet);
        o.put("savePath", savePath);
        o.put("totalBytes", totalBytes);
        o.put("downloadedBytes", downloadedBytes);
        o.put("sequential", sequential);
        o.put("startImmediately", startImmediately);
        o.put("addedAt", addedAt);
        o.put("completedAt", completedAt);
        o.put("state", state.name());
        o.put("lastError", lastError);
        if (torrentBytes != null && torrentBytes.length > 0) {
            o.put("torrentB64", android.util.Base64.encodeToString(torrentBytes, android.util.Base64.NO_WRAP));
        }
        JSONArray arr = new JSONArray();
        if (filePriorities != null) {
            for (int p : filePriorities) {
                arr.put(p);
            }
        }
        o.put("filePriorities", arr);
        return o;
    }

    public static TorrentTask fromJson(JSONObject o) {
        TorrentTask t = new TorrentTask();
        t.infoHash = o.optString("infoHash", null);
        t.name = o.optString("name", null);
        t.magnet = o.optString("magnet", null);
        t.savePath = o.optString("savePath", null);
        t.totalBytes = o.optLong("totalBytes", -1L);
        t.downloadedBytes = o.optLong("downloadedBytes", 0L);
        t.sequential = o.optBoolean("sequential", false);
        t.startImmediately = o.optBoolean("startImmediately", true);
        t.addedAt = o.optLong("addedAt", System.currentTimeMillis());
        t.completedAt = o.optLong("completedAt", 0L);
        t.lastError = o.optString("lastError", null);
        String b64 = o.optString("torrentB64", null);
        if (b64 != null && !b64.isEmpty()) {
            try {
                t.torrentBytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            } catch (Throwable ignored) {
                t.torrentBytes = null;
            }
        }
        try {
            t.state = State.valueOf(o.optString("state", State.QUEUED.name()));
        } catch (IllegalArgumentException e) {
            t.state = State.QUEUED;
        }
        JSONArray arr = o.optJSONArray("filePriorities");
        if (arr != null) {
            t.filePriorities = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                t.filePriorities[i] = arr.optInt(i, 4);
            }
        }
        return t;
    }
}
