package com.magnetrush.app.core;

import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentStatus;

/**
 * 某一个任务的实时状态快照。
 *
 * <p>刻意做成「不可变值对象 + 纯 Java 枚举」，不带任何 libtorrent 类型暴露给 UI 层，
 * 这样界面代码永远不会因为 JNI 对象被释放而崩溃。
 */
public final class TaskStatus {

    public enum State {
        /** 正在校验已有数据（断点续传后首次启动常见） */
        CHECKING,
        /** 正在从 DHT/Tracker 找元数据（磁力链接刚添加时的阶段） */
        FETCHING_METADATA,
        /** 已拿到元数据，正在排队连接 */
        QUEUED,
        /** 正在下载 */
        DOWNLOADING,
        /** 下载完成，正在做种（默认做完就停，见设置） */
        SEEDING,
        /** 已全部下载完成 */
        FINISHED,
        /** 用户暂停 */
        PAUSED,
        /** 出错 */
        ERROR
    }

    public final State state;

    /** 任务显示名（Torrent 内的名字，元数据到手后才有） */
    public final String name;

    /** 0f ~ 1f */
    public final float progress;

    /** 瞬时下载速度（字节/秒） */
    public final long downloadRate;

    /** 瞬时上传速度（字节/秒） */
    public final long uploadRate;

    /** 已下载的有效载荷字节数 */
    public final long downloadedBytes;

    /** 已上传字节数 */
    public final long uploadedBytes;

    /** 元数据里声明的总大小；元数据未知时为 -1 */
    public final long totalBytes;

    /** 已连接上的节点数 */
    public final int peers;

    /** 其中已连上的种子数 */
    public final int seeds;

    /** 当前文件数；元数据未知时为 0 */
    public final int fileCount;

    /** 错误描述，state == ERROR 时才有值 */
    public final String errorMessage;

    /** 是否处于顺序下载模式（边下边播场景） */
    public final boolean sequential;

    /** 是否已完成校验并进入做种状态 */
    public final boolean isSeeding;

    public TaskStatus(State state, String name, float progress, long downloadRate, long uploadRate,
                      long downloadedBytes, long uploadedBytes, long totalBytes,
                      int peers, int seeds, int fileCount, String errorMessage,
                      boolean sequential, boolean isSeeding) {
        this.state = state;
        this.name = name;
        this.progress = progress;
        this.downloadRate = downloadRate;
        this.uploadRate = uploadRate;
        this.downloadedBytes = downloadedBytes;
        this.uploadedBytes = uploadedBytes;
        this.totalBytes = totalBytes;
        this.peers = peers;
        this.seeds = seeds;
        this.fileCount = fileCount;
        this.errorMessage = errorMessage;
        this.sequential = sequential;
        this.isSeeding = isSeeding;
    }

    /** 一个「什么都没开始」的占位状态 */
    public static TaskStatus idle(String name) {
        return new TaskStatus(State.QUEUED, name, 0f, 0L, 0L, 0L, 0L, -1L,
                0, 0, 0, null, false, false);
    }

    public boolean isActive() {
        return state == State.DOWNLOADING || state == State.SEEDING
                || state == State.CHECKING || state == State.FETCHING_METADATA
                || state == State.QUEUED;
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Override
    public String toString() {
        return "TaskStatus{" + state + ", " + (progress * 100f) + "%, down=" + downloadRate
                + "B/s, peers=" + peers + "}";
    }

    /** 把 libtorrent 的状态枚举翻译成本应用的枚举 */
    static State mapTorrentState(TorrentStatus.State s) {
        if (s == null) {
            return State.QUEUED;
        }
        switch (s) {
            case CHECKING_FILES:
            case CHECKING_RESUME_DATA:
                return State.CHECKING;
            case DOWNLOADING_METADATA:
                return State.FETCHING_METADATA;
            case DOWNLOADING:
                return State.DOWNLOADING;
            case FINISHED:
                return State.FINISHED;
            case SEEDING:
                return State.SEEDING;
            case UNKNOWN:
            default:
                return State.QUEUED;
        }
    }
}
