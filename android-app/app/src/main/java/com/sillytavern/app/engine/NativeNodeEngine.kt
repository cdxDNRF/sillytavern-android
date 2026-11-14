package com.sillytavern.app.engine

import android.net.ConnectivityManager

object NativeNodeEngine {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("st_bridge")
    }

    external fun nativeInitDns(connectivityManager: ConnectivityManager?): Boolean

    external fun nativeStartNode(
        appDir: String,
        dataRoot: String,
        configPath: String,
        cacheDir: String,
        port: Int,
        appToken: String,
        callback: EngineLifecycleCallback,
    ): Int

    external fun nativeStopNode()

    external fun nativeGetRecentLogs(): String
}
