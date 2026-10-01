package com.magnetrush.app.util;

import java.util.Locale;

/** 各种人类可读格式化。 */
public final class Fmt {

    private Fmt() {
    }

    /** 字节数 → 可读字符串 */
    public static String size(long bytes) {
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
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format(Locale.US, "%.2f GB", gb);
        }
        return String.format(Locale.US, "%.2f TB", gb / 1024.0);
    }

    /** 速度 → 可读字符串 */
    public static String speed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 KB/s";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB/s", kb);
        }
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.2f MB/s", mb);
    }

    /** 进度 0~1 → 百分比 */
    public static String percent(float progress) {
        return String.format(Locale.US, "%.1f%%", Math.max(0f, Math.min(1f, progress)) * 100f);
    }

    /**
     * 剩余时间估算。
     *
     * @param remainBytes 剩余字节
     * @param rate        当前速度（字节/秒）
     */
    public static String eta(long remainBytes, long rate) {
        if (rate <= 0 || remainBytes <= 0) {
            return "--";
        }
        long sec = remainBytes / rate;
        if (sec < 60) {
            return sec + " 秒";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + " 分钟";
        }
        long hour = min / 60;
        if (hour < 24) {
            return hour + " 小时 " + (min % 60) + " 分";
        }
        return (hour / 24) + " 天 " + (hour % 24) + " 小时";
    }

    /** 时间戳 → 相对时间描述 */
    public static String ago(long timestampMs) {
        if (timestampMs <= 0) {
            return "--";
        }
        long diff = System.currentTimeMillis() - timestampMs;
        if (diff < 60_000L) {
            return "刚刚";
        }
        long min = diff / 60_000L;
        if (min < 60) {
            return min + " 分钟前";
        }
        long hour = min / 60;
        if (hour < 24) {
            return hour + " 小时前";
        }
        return (hour / 24) + " 天前";
    }
}
