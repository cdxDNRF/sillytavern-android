package com.sillytavern.app.engine

import android.net.ConnectivityManager

object NativeNodeEngine {
    @Volatile
    private var librariesLoaded = false

    fun isLoaded(): Boolean = librariesLoaded

    @Synchronized
    fun ensureLoaded(onMilestone: (String) -> Unit = {}) {
        if (librariesLoaded) return
        onMilestone("Loading native library: libc++_shared.so")
        System.loadLibrary("c++_shared")
        onMilestone("Loading native library: libnode.so")
        System.loadLibrary("node")
        onMilestone("Loading native library: libst_bridge.so")
        System.loadLibrary("st_bridge")
        librariesLoaded = true
        onMilestone("All native libraries loaded cleanly")
    }

    external fun nativeSetDiagDir(diagDir: String)

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
