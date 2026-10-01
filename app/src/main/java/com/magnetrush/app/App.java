package com.magnetrush.app;

import android.app.Application;
import android.util.Log;

import org.libtorrent4j.SessionManager;

/**
 * 进程入口。
 *
 * <p>唯一的全局初始化就是加载 libtorrent 的 native 库（libjlibtorrent.so）。
 * 这一步必须在任何 JNI 调用之前完成。这里用「构造一个 {@link SessionManager}」
 * 来触发类加载与 {@code System.loadLibrary}，是最可靠的方式 ——
 * 相比调用某个具体的 load() 方法，不依赖该版本是否暴露了那个方法。
 *
 * <p>构造后立刻 {@code stop()} 掉这个临时会话，避免白白占着端口。
 */
public class App extends Application {

    private static final String TAG = "MagnetRush";

    private static volatile boolean nativeLoaded = false;
    private static volatile String nativeError;

    @Override
    public void onCreate() {
        super.onCreate();
        loadNative();
    }

    /**
     * 加载 libtorrent 的 .so。
     *
     * @return 是否加载成功；失败时界面会提示「设备架构不支持」
     */
    public static boolean loadNative() {
        if (nativeLoaded) {
            return true;
        }
        synchronized (App.class) {
            if (nativeLoaded) {
                return true;
            }
            try {
                SessionManager probe = new SessionManager();
                probe.stop();
                nativeLoaded = true;
                nativeError = null;
                Log.i(TAG, "libtorrent native loaded");
            } catch (Throwable t) {
                nativeLoaded = false;
                nativeError = String.valueOf(t);
                Log.e(TAG, "libtorrent native load failed", t);
            }
            return nativeLoaded;
        }
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    public static String nativeError() {
        return nativeError;
    }
}
