package com.tv.neighbor;

import android.app.Application;

public class App extends Application {
    private static App app;

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
    }

    /** 进程级 Context（播放器磁盘缓存等无界面组件用）。 */
    public static App instance() {
        return app;
    }
}
