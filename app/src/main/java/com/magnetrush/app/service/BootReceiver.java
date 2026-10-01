package com.magnetrush.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.magnetrush.app.core.TaskStore;

/**
 * 开机后把下载服务重新拉起来。
 *
 * <p>只在确实还有未完成任务时才启动，避免用户明明没有下载任务却被拉起一个前台服务。
 * 注意：Android 高版本对开机自启有限制，被系统拦掉也没关系 —— 用户下次打开 App
 * 一样会自动恢复（见 {@code DownloadService.restorePendingTasks()}）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            boolean hasUnfinished = false;
            for (com.magnetrush.app.core.TorrentTask t : TaskStore.get(context).all()) {
                if (!t.isCompleted()) {
                    hasUnfinished = true;
                    break;
                }
            }
            if (hasUnfinished) {
                DownloadService.start(context.getApplicationContext());
            }
        } catch (Throwable ignored) {
            // 开机阶段资源紧张，失败就算了
        }
    }
}
