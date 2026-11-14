package com.sillytavern.app.engine

interface EngineLifecycleCallback {
    fun onReady(port: Int, url: String)
    fun onError(message: String)
}
