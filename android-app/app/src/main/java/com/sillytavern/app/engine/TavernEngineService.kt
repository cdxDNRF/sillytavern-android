package com.sillytavern.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sillytavern.app.MainActivity
import com.sillytavern.app.R
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class TavernEngineService : Service() {

    companion object {
        private const val TAG = "TavernEngineService"
        private const val CHANNEL_ID = "sillytavern_engine_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.sillytavern.app.action.START_ENGINE"
        const val ACTION_RESTART = "com.sillytavern.app.action.RESTART_ENGINE"
        const val ACTION_STOP = "com.sillytavern.app.action.STOP_ENGINE"
    }

    private val callbacks = RemoteCallbackList<ITavernEngineCallback>()
    private val engineStartingOrRunning = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)
    private val nativeLibraryLoaded = AtomicBoolean(false)

    private val serviceLogLock = Any()
    private val serviceLogs = ArrayDeque<String>()

    @Volatile
    private var currentState: Int = EngineState.IDLE

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var boundUrl: String = ""

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var installedAssetVersion: String = ""

    @Volatile
    private var sessionToken: String = generateSessionToken()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "STNodeMain", 8L * 1024L * 1024L)
    }

    private val binder = object : ITavernEngine.Stub() {
        override fun registerCallback(cb: ITavernEngineCallback?) {
            if (cb == null) return
            callbacks.register(cb)
            try {
                cb.onStateChanged(currentState, boundPort, boundUrl, lastError)
            } catch (_: Exception) {
                // ignore dead client
            }
        }

        override fun unregisterCallback(cb: ITavernEngineCallback?) {
            if (cb != null) {
                callbacks.unregister(cb)
            }
        }

        override fun startEngine() {
            ensureEngineStarted()
        }

        override fun stopEngine() {
            restartPending.set(false)
            shutdownEngine(stopSelfAfter = true)
        }

        override fun getCurrentState(): Int = currentState

        override fun getBoundPort(): Int = boundPort

        override fun getBoundUrl(): String = boundUrl

        override fun getAppToken(): String = sessionToken

        override fun getLastError(): String = lastError

        override fun getRecentLogs(): String {
            val kotlinLogs = synchronized(serviceLogLock) {
                serviceLogs.joinToString("\n")
            }
            val nativeLogs = if (nativeLibraryLoaded.get()) {
                try {
                    NativeNodeEngine.nativeGetRecentLogs()
                } catch (t: Throwable) {
                    "Unable to fetch native logs: ${t.message}"
                }
            } else {
                ""
            }
            return buildString {
                if (kotlinLogs.isNotBlank()) {
                    append(kotlinLogs)
                }
                if (nativeLogs.isNotBlank()) {
                    if (isNotEmpty()) append("\n--- Node.js Runtime Logs ---\n")
                    append(nativeLogs)
                }
            }
        }

        override fun getInstalledAssetVersion(): String = installedAssetVersion
    }

    override fun onCreate() {
        super.onCreate()
        appendServiceLog("TavernEngineService.onCreate (pid=${android.os.Process.myPid()})")
        runCatching { createNotificationChannel() }
            .onFailure { appendServiceLog("createNotificationChannel warning: ${it.message}") }
        installedAssetVersion = runCatching { AssetExtractor.readInstalledSha(this) }.getOrDefault("")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        appendServiceLog("onStartCommand action=${action ?: "<null>"}")
        when (action) {
            ACTION_STOP -> {
                restartPending.set(false)
                shutdownEngine(stopSelfAfter = true)
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                promoteForeground("正在重启本地 SillyTavern 引擎...")
                requestRestart()
                return START_NOT_STICKY
            }
            else -> {
                promoteForeground("正在启动本地 SillyTavern 引擎...")
                ensureEngineStarted()
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        appendServiceLog("TavernEngineService.onBind")
        return binder
    }

    override fun onDestroy() {
        appendServiceLog("TavernEngineService.onDestroy")
        restartPending.set(false)
        shutdownEngine(stopSelfAfter = false)
        engineExecutor.shutdown()
        callbacks.kill()
        super.onDestroy()
    }

    private fun appendServiceLog(msg: String) {
        Log.i(TAG, msg)
        val stamped = "[Service] $msg"
        synchronized(serviceLogLock) {
            serviceLogs.addLast(stamped)
            while (serviceLogs.size > 150) {
                serviceLogs.removeFirst()
            }
        }
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun promoteForeground(statusText: String) {
        runCatching {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure { t ->
            appendServiceLog("promoteForeground skipped (${t.javaClass.simpleName}): ${t.message}")
        }
    }

    private fun updateNotification(statusText: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIF_ID, buildNotification(statusText))
        }.onFailure { t ->
            Log.w(TAG, "updateNotification failed: ${t.message}")
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, TavernEngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tavern)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "停止引擎", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.engine_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.engine_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquirePowerLocks() {
        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyTavern:EngineWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(6L * 60L * 60L * 1000L)
                }
            }.onFailure { t ->
                appendServiceLog("WakeLock acquisition skipped: ${t.message}")
            }
        }
        if (wifiLock == null) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SillyTavern:EngineWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { t ->
                appendServiceLog("WifiLock acquisition skipped: ${t.message}")
            }
        }
    }

    private fun releasePowerLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun publishState(state: Int, port: Int = boundPort, url: String = boundUrl, detail: String = "") {
        currentState = state
        boundPort = port
        boundUrl = url
        if (state == EngineState.ERROR) {
            lastError = detail
        }
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onStateChanged(state, port, url, detail)
                } catch (_: Exception) {
                    // ignore disconnected clients
                }
            }
            callbacks.finishBroadcast()
        }
    }

    private fun ensureEngineStarted() {
        if (!engineStartingOrRunning.compareAndSet(false, true)) {
            appendServiceLog("ensureEngineStarted ignored: already starting/running (state=$currentState)")
            return
        }

        try {
            if (sessionToken.isEmpty()) {
                sessionToken = generateSessionToken()
            }
            boundPort = 0
            boundUrl = ""
            lastError = ""
            publishState(EngineState.EXTRACTING, 0, "", "正在检查并准备本地运行环境...")
            promoteForeground("正在准备 SillyTavern 运行环境...")
            acquirePowerLocks()

            // Run Node/V8 with a generous 8 MB native stack for deep JS/WASM execution on arm64.
            engineExecutor.execute {
                try {
                    appendServiceLog("Ensuring SillyTavern assets prepared in app filesDir...")
                    val prepared = AssetExtractor.ensurePrepared(applicationContext) { progress ->
                        publishState(EngineState.EXTRACTING, 0, "", progress)
                        updateNotification(progress)
                    }
                    installedAssetVersion = prepared.assetHash
                    appendServiceLog("Assets prepared (sha=${prepared.assetHash.take(12)}, appDir=${prepared.appDir.absolutePath})")

                    publishState(EngineState.STARTING, 0, "", "正在加载嵌入式 Node.js 运行时...")
                    updateNotification("正在启动本地 SillyTavern 服务...")

                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    appendServiceLog("Loading libnode.so + libst_bridge.so...")
                    val dnsOk = NativeNodeEngine.nativeInitDns(cm)
                    nativeLibraryLoaded.set(true)
                    appendServiceLog("Native runtime loaded (dnsInit=$dnsOk), launching Node event loop...")

                    publishState(EngineState.STARTING, 0, "", "正在启动嵌入式 Node.js 服务...")
                    val exitCode = NativeNodeEngine.nativeStartNode(
                        appDir = prepared.appDir.absolutePath,
                        dataRoot = prepared.dataRoot.absolutePath,
                        configPath = prepared.configPath.absolutePath,
                        cacheDir = prepared.cacheDir.absolutePath,
                        port = 0,
                        appToken = sessionToken,
                        callback = object : EngineLifecycleCallback {
                            override fun onReady(port: Int, url: String) {
                                val resolvedUrl = if (url.startsWith("http")) url else "http://127.0.0.1:$port"
                                appendServiceLog("SillyTavern HTTP server ready on $resolvedUrl")
                                updateNotification("SillyTavern 本地引擎运行中（端口 $port）")
                                publishState(EngineState.READY, port, resolvedUrl, "")
                            }

                            override fun onError(message: String) {
                                appendServiceLog("Node startup error callback: $message")
                                publishState(EngineState.ERROR, 0, "", message)
                            }
                        },
                    )

                    appendServiceLog("Node event loop exited with code=$exitCode (state=$currentState)")
                    if (currentState != EngineState.STOPPED && currentState != EngineState.ERROR) {
                        if (exitCode != 0) {
                            publishState(EngineState.ERROR, 0, "", "Node.js 引擎意外退出 (exitCode=$exitCode)")
                        } else {
                            publishState(EngineState.STOPPED, 0, "", "")
                        }
                    }
                } catch (t: Throwable) {
                    appendServiceLog("Failed inside engine thread: ${t.javaClass.name}: ${t.message}")
                    Log.e(TAG, "Failed inside engine thread", t)
                    publishState(EngineState.ERROR, 0, "", t.stackTraceToString())
                } finally {
                    engineStartingOrRunning.set(false)
                    releasePowerLocks()
                    if (restartPending.compareAndSet(true, false)) {
                        sessionToken = generateSessionToken()
                        ensureEngineStarted()
                    }
                }
            }
        } catch (t: Throwable) {
            appendServiceLog("Failed scheduling engine start: ${t.javaClass.name}: ${t.message}")
            engineStartingOrRunning.set(false)
            releasePowerLocks()
            publishState(EngineState.ERROR, 0, "", t.stackTraceToString())
        }
    }

    private fun requestRestart() {
        restartPending.set(true)
        publishState(EngineState.STARTING, 0, "", "正在重启本地引擎...")
        if (engineStartingOrRunning.get() && nativeLibraryLoaded.get()) {
            try {
                NativeNodeEngine.nativeStopNode()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeStopNode error during restart: ${t.message}")
            }
        }
        if (!engineStartingOrRunning.get() && restartPending.compareAndSet(true, false)) {
            sessionToken = generateSessionToken()
            ensureEngineStarted()
        }
    }

    private fun shutdownEngine(stopSelfAfter: Boolean) {
        publishState(EngineState.STOPPED, 0, "", "")
        if (nativeLibraryLoaded.get()) {
            try {
                NativeNodeEngine.nativeStopNode()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeStopNode error: ${t.message}")
            }
        }
        releasePowerLocks()
        if (stopSelfAfter) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
        }
    }
}
