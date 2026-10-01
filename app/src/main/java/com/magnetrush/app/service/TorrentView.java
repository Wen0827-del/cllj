package com.magnetrush.app.service;

import androidx.annotation.Nullable;

import com.magnetrush.app.core.TaskStatus;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 服务 ↔ 界面之间的可序列化状态快照。
 *
 * <p>为什么不用 LocalBroadcastManager 传对象：跨进程/跨版本序列化用 JSON 最稳，
 * 而且字段一目了然，方便调试。
 */
public final class TorrentView {

    public String infoHash = "";
    public String name = "";
    /** {@link TaskStatus.State#name()} */
    public String state = TaskStatus.State.QUEUED.name();
    public float progress = 0f;
    public long downloadRate = 0L;
    public long uploadRate = 0L;
    public long downloadedBytes = 0L;
    public long uploadedBytes = 0L;
    public long totalBytes = -1L;
    public int peers = 0;
    public int seeds = 0;
    public int fileCount = 0;
    public boolean sequential = false;
    public boolean completed = false;
    public long completedAt = 0L;
    public String savePath = "";
    public String error = null;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("infoHash", infoHash);
            o.put("name", name);
            o.put("state", state);
            o.put("progress", progress);
            o.put("downloadRate", downloadRate);
            o.put("uploadRate", uploadRate);
            o.put("downloadedBytes", downloadedBytes);
            o.put("uploadedBytes", uploadedBytes);
            o.put("totalBytes", totalBytes);
            o.put("peers", peers);
            o.put("seeds", seeds);
            o.put("fileCount", fileCount);
            o.put("sequential", sequential);
            o.put("completed", completed);
            o.put("completedAt", completedAt);
            o.put("savePath", savePath);
            o.put("error", error);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static TorrentView fromJson(JSONObject o) {
        TorrentView v = new TorrentView();
        v.infoHash = o.optString("infoHash", "");
        v.name = o.optString("name", "");
        v.state = o.optString("state", TaskStatus.State.QUEUED.name());
        v.progress = (float) o.optDouble("progress", 0d);
        v.downloadRate = o.optLong("downloadRate", 0L);
        v.uploadRate = o.optLong("uploadRate", 0L);
        v.downloadedBytes = o.optLong("downloadedBytes", 0L);
        v.uploadedBytes = o.optLong("uploadedBytes", 0L);
        v.totalBytes = o.optLong("totalBytes", -1L);
        v.peers = o.optInt("peers", 0);
        v.seeds = o.optInt("seeds", 0);
        v.fileCount = o.optInt("fileCount", 0);
        v.sequential = o.optBoolean("sequential", false);
        v.completed = o.optBoolean("completed", false);
        v.completedAt = o.optLong("completedAt", 0L);
        v.savePath = o.optString("savePath", "");
        String err = o.optString("error", "");
        v.error = err.isEmpty() ? null : err;
        return v;
    }

    public TaskStatus.State stateEnum() {
        try {
            return TaskStatus.State.valueOf(state);
        } catch (Exception e) {
            return TaskStatus.State.QUEUED;
        }
    }

    public boolean isRunning() {
        TaskStatus.State s = stateEnum();
        return s == TaskStatus.State.DOWNLOADING
                || s == TaskStatus.State.SEEDING
                || s == TaskStatus.State.CHECKING
                || s == TaskStatus.State.FETCHING_METADATA
                || s == TaskStatus.State.QUEUED;
    }

    public boolean isPaused() {
        return stateEnum() == TaskStatus.State.PAUSED;
    }

    public boolean isError() {
        return stateEnum() == TaskStatus.State.ERROR;
    }

    /** 剩余字节，未知返回 -1 */
    public long remainBytes() {
        if (totalBytes <= 0) {
            return -1L;
        }
        return Math.max(0L, totalBytes - downloadedBytes);
    }

    @Nullable
    public static TorrentView fromJsonOrNull(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }
}
