package com.magnetrush.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏按钮的接收器。
 *
 * <p>不做任何业务，只把 action 原样转发给 {@link DownloadService}。
 * 放在这里而不是直接指向服务，是因为通知里的 PendingIntent 指向 Service 时
 * Android 12+ 对后台启动前台服务有限制，走一层 receiver 更稳。
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        DownloadService.dispatch(context.getApplicationContext(), new Intent(intent));
    }
}
