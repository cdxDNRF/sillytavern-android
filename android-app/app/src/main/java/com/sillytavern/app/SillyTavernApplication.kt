package com.sillytavern.app

import android.app.Application
import android.os.Build
import android.os.Process
import com.sillytavern.app.diag.DiagLogStore

/**
 * Custom Application running at the start of both the UI process and the isolated :engine process.
 * Installs persistent crash telemetry inside app-private filesDir/diag/ so unexpected faults
 * never go silent.
 */
class SillyTavernApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        runCatching {
            installUncaughtExceptionLogger()
            DiagLogStore.appendEvent(
                applicationContext,
                "ProcessStart",
                "proc=${DiagLogStore.processTag(applicationContext)} pid=${Process.myPid()} " +
                    "sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
            )
        }
    }

    private fun installUncaughtExceptionLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagLogStore.recordJavaCrash(applicationContext, thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
