package com.sillytavern.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sillytavern.app.bridge.ExportBridge
import com.sillytavern.app.bridge.ExportItem
import com.sillytavern.app.bridge.ExportRepository
import com.sillytavern.app.bridge.TavernWebChromeClient
import com.sillytavern.app.diag.DiagLogStore
import com.sillytavern.app.engine.EngineState
import com.sillytavern.app.engine.ITavernEngine
import com.sillytavern.app.engine.ITavernEngineCallback
import com.sillytavern.app.engine.TavernEngineService
import com.sillytavern.app.ui.BootOverlay
import com.sillytavern.app.ui.EngineDiagnosticsDialog
import com.sillytavern.app.ui.ExportManagerSheet
import com.sillytavern.app.ui.FloatingControlHandle
import com.sillytavern.app.ui.SillyTavernAppTheme
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var exportRepository: ExportRepository
    private lateinit var exportBridge: ExportBridge
    private lateinit var webChromeClient: TavernWebChromeClient

    private var uiSetupFailed = false
    private var engineBinder: ITavernEngine? = null
    private var bindRequested = false
    private var serviceConnected = false
    private var notificationPermissionRequested = false
    private var startupWatchdogJob: Job? = null

    private var engineState by mutableIntStateOf(EngineState.IDLE)
    private var enginePort by mutableIntStateOf(0)
    private var engineDetail by mutableStateOf("")
    private var bootElapsedSeconds by mutableIntStateOf(0)
    private var assetVersion by mutableStateOf("")
    private var logsText by mutableStateOf("")
    private var exportsList by mutableStateOf<List<ExportItem>>(emptyList())

    private var webViewPageLoaded by mutableStateOf(false)
    private var showExportSheet by mutableStateOf(false)
    private var showDiagnosticsDialog by mutableStateOf(false)
    private var pendingExternalUrl by mutableStateOf<String?>(null)
    private var showExitConfirmDialog by mutableStateOf(false)

    private var webViewRef: WebView? = null
    private var lastLoadedSessionKey: String = ""
    private var pendingFileChooserCallback: ((Int, Intent?) -> Unit)? = null
    private var pendingSaveItem: ExportItem? = null

    private val snackbarHostState = SnackbarHostState()

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Intent>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private val engineCallback = object : ITavernEngineCallback.Stub() {
        override fun onStateChanged(stateCode: Int, port: Int, url: String?, detail: String?) {
            runOnUiThread {
                handleEngineStateUpdate(stateCode, port, detail ?: "")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runOnUiThread {
                if (!bindRequested) return@runOnUiThread
                val binder = ITavernEngine.Stub.asInterface(service)
                if (binder == null) {
                    unbindEngineSafely()
                    reportEngineProcessFailure("引擎服务返回了空的 Binder 接口。")
                    return@runOnUiThread
                }
                engineBinder = binder
                serviceConnected = true
                DiagLogStore.appendEvent(applicationContext, "MainActivity", "onServiceConnected(:engine)")
                try {
                    binder.registerCallback(engineCallback)
                    assetVersion = binder.installedAssetVersion ?: ""
                    val state = binder.currentState
                    val port = binder.boundPort
                    val detail = binder.lastError ?: ""
                    handleEngineStateUpdate(state, port, detail)
                    if (state == EngineState.IDLE || state == EngineState.STOPPED) {
                        binder.startEngine()
                    }
                    refreshEngineLogs()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error querying bound engine service", t)
                    DiagLogStore.appendEvent(
                        applicationContext,
                        "MainActivity",
                        "Binder query failed: ${t.javaClass.simpleName}: ${t.message}",
                    )
                    unbindEngineSafely()
                    reportEngineProcessFailure(
                        "连接本地引擎服务失败 (${t.javaClass.simpleName}): ${t.message ?: "未知异常"}",
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runOnUiThread {
                if (!bindRequested && !serviceConnected) return@runOnUiThread
                val wasIntentionalStop = (engineState == EngineState.STOPPED)
                DiagLogStore.appendEvent(
                    applicationContext,
                    "MainActivity",
                    "onServiceDisconnected(:engine) prevState=$engineState intentional=$wasIntentionalStop",
                )
                unbindEngineSafely()
                webViewPageLoaded = false
                lastLoadedSessionKey = ""
                if (!wasIntentionalStop) {
                    reportEngineProcessFailure("与本地后台服务的连接已断开 (:engine 进程意外终止)。")
                }
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            runOnUiThread {
                if (!bindRequested && !serviceConnected) return@runOnUiThread
                val wasIntentionalStop = (engineState == EngineState.STOPPED)
                DiagLogStore.appendEvent(
                    applicationContext,
                    "MainActivity",
                    "onBindingDied(:engine) prevState=$engineState intentional=$wasIntentionalStop",
                )
                unbindEngineSafely()
                webViewPageLoaded = false
                lastLoadedSessionKey = ""
                if (!wasIntentionalStop) {
                    reportEngineProcessFailure("本地后台服务连接已失效 (:engine 进程已退出)。")
                }
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            runOnUiThread {
                DiagLogStore.appendEvent(applicationContext, "MainActivity", "onNullBinding(:engine)")
                unbindEngineSafely()
                reportEngineProcessFailure("本地引擎服务返回空绑定 (onNullBinding)。")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)

        openDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val callback = pendingFileChooserCallback
            pendingFileChooserCallback = null
            callback?.invoke(result.resultCode, result.data)
        }

        createDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val item = pendingSaveItem
            pendingSaveItem = null
            val targetUri = result.data?.data
            if (result.resultCode == RESULT_OK && item != null && targetUri != null) {
                saveExportToDeviceUri(item, targetUri)
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            // Foreground service operates cleanly regardless of notification visibility.
        }

        try {
            DiagLogStore.appendEvent(applicationContext, "MainActivity", "onCreate()")

            exportRepository = ExportRepository(applicationContext).apply {
                cleanupPartialFiles()
            }
            exportsList = exportRepository.listExports()

            exportBridge = ExportBridge(
                repository = exportRepository,
                onExportCompleted = { item ->
                    exportsList = exportRepository.listExports()
                    showSnackWithExportAction("已保存到应用导出箱: ${item.name}")
                },
                onExportFailed = { msg ->
                    showSnack(msg)
                },
            )

            webChromeClient = TavernWebChromeClient(
                launchFileChooser = { intent, callback ->
                    pendingFileChooserCallback?.invoke(RESULT_CANCELED, null)
                    pendingFileChooserCallback = callback
                    openDocumentLauncher.launch(intent)
                },
                onLocalUrlRequested = { url ->
                    if (isLoopbackUrl(url)) {
                        webViewRef?.loadUrl(url)
                    }
                },
                onExternalUrlRequested = { url ->
                    pendingExternalUrl = url
                },
            )

            startAndBindEngineService()
            startStartupWatchdog()

            setContent {
                SillyTavernAppTheme {
                    MainScreen()
                }
            }
        } catch (t: Throwable) {
            uiSetupFailed = true
            Log.e(TAG, "Fatal error in MainActivity.onCreate", t)
            DiagLogStore.recordJavaCrash(applicationContext, Thread.currentThread(), t)
            showNativeFallbackErrorView(t)
        }
    }

    override fun onStart() {
        super.onStart()
        if (uiSetupFailed) return
        if (::exportRepository.isInitialized) {
            exportsList = exportRepository.listExports()
        }
        if (!bindRequested && engineState != EngineState.ERROR && engineState != EngineState.STOPPED) {
            startAndBindEngineService()
            startStartupWatchdog()
        }
    }

    override fun onResume() {
        super.onResume()
        webViewRef?.onResume()
    }

    override fun onPause() {
        webViewRef?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        startupWatchdogJob?.cancel()
        if (::webChromeClient.isInitialized) {
            webChromeClient.cancelPendingFileChooser()
        }
        if (::exportBridge.isInitialized) {
            exportBridge.releaseAll()
        }
        unbindEngineSafely()
        webViewRef?.let { wv ->
            wv.stopLoading()
            wv.removeJavascriptInterface("STAndroidBridge")
            wv.destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    private fun showNativeFallbackErrorView(throwable: Throwable) {
        val report = runCatching {
            DiagLogStore.readCombinedDiagnostics(applicationContext, throwable.stackTraceToString())
        }.getOrElse {
            "${throwable.javaClass.name}: ${throwable.message}\n${throwable.stackTraceToString()}"
        }

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.parseColor("#121318"))
            setPadding(pad, (42 * dp).toInt(), pad, pad)
        }

        val title = TextView(this).apply {
            text = "SillyTavern 界面初始化异常"
            setTextColor(AndroidColor.parseColor("#FF6B6B"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "${throwable.javaClass.simpleName}: ${throwable.message ?: "未知异常"}\n请点击「复制完整日志」发送排查。"
            setTextColor(AndroidColor.parseColor("#ECEEF4"))
            textSize = 13f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#0C0D11"))
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        val logView = TextView(this).apply {
            text = report
            setTextColor(AndroidColor.parseColor("#D6DCE8"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll.addView(logView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        val copyBtn = Button(this).apply {
            text = "复制完整日志"
            setOnClickListener {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SillyTavern UI Crash", report))
                    Toast.makeText(this@MainActivity, "已复制诊断日志", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val exitBtn = Button(this).apply {
            text = "关闭退出"
            setOnClickListener { finishAndRemoveTask() }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(exitBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(title)
        root.addView(subtitle)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnRow)
        setContentView(root)
    }

    @Composable
    private fun MainScreen() {
        BackHandler(enabled = true) {
            when {
                showExitConfirmDialog -> showExitConfirmDialog = false
                pendingExternalUrl != null -> pendingExternalUrl = null
                showDiagnosticsDialog -> showDiagnosticsDialog = false
                showExportSheet -> showExportSheet = false
                else -> handleWebViewBackPress()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121318))
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
        ) {
            if (engineState == EngineState.READY || webViewRef != null) {
                AndroidView(
                    factory = { ctx -> safeCreateWebView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = engineState != EngineState.READY || !webViewPageLoaded,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BootOverlay(
                    state = engineState,
                    statusDetail = engineDetail,
                    logsPreview = logsText,
                    elapsedSeconds = bootElapsedSeconds,
                    onRetry = { retryEngineStart() },
                    onCopyLogs = { copyLogsToClipboard() },
                    onOpenLogs = {
                        refreshEngineLogs()
                        showDiagnosticsDialog = true
                    },
                    onRefreshLogs = { refreshEngineLogs() },
                )
            }

            if (engineState == EngineState.READY && webViewPageLoaded) {
                FloatingControlHandle(
                    exportCount = exportsList.size,
                    onClick = {
                        exportsList = exportRepository.listExports()
                        showExportSheet = true
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        if (showExportSheet) {
            ExportManagerSheet(
                exports = exportsList,
                boundPort = enginePort,
                onDismiss = { showExportSheet = false },
                onSaveToDevice = { item -> launchSaveToDevice(item) },
                onShare = { item -> shareExportItem(item) },
                onDeleteItem = { item ->
                    exportRepository.deleteExport(item)
                    exportsList = exportRepository.listExports()
                    showSnack("已删除 ${item.name}")
                },
                onClearAll = {
                    exportRepository.clearAll()
                    exportsList = exportRepository.listExports()
                    showSnack("已清空应用导出箱")
                },
                onOpenDiagnostics = {
                    refreshEngineLogs()
                    showExportSheet = false
                    showDiagnosticsDialog = true
                },
                onReloadWebView = {
                    showExportSheet = false
                    reloadWebViewWithBootstrap()
                },
            )
        }

        if (showDiagnosticsDialog) {
            EngineDiagnosticsDialog(
                boundPort = enginePort,
                assetVersion = assetVersion,
                logsText = logsText,
                onDismiss = { showDiagnosticsDialog = false },
                onRefreshLogs = { refreshEngineLogs() },
                onCopyLogs = { copyLogsToClipboard() },
                onRestartEngine = {
                    showDiagnosticsDialog = false
                    restartEngineFromUi()
                },
            )
        }

        pendingExternalUrl?.let { externalUrl ->
            AlertDialog(
                onDismissRequest = { pendingExternalUrl = null },
                title = { Text("打开外部链接？") },
                text = {
                    Text(
                        text = "SillyTavern 仅在应用内安全沙盒中运行。以下链接指向应用外部地址，是否使用系统浏览器打开？\n\n$externalUrl",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingExternalUrl = null
                            openInExternalBrowser(externalUrl)
                        },
                    ) {
                        Text("打开外部浏览器")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingExternalUrl = null }) {
                        Text("留在本应用内")
                    }
                },
            )
        }

        if (showExitConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showExitConfirmDialog = false },
                title = { Text("退出 SillyTavern？") },
                text = {
                    Text(
                        text = "你可以选择将本地引擎转入后台继续生成，或完全停止后台 Node.js 服务并退出应用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitConfirmDialog = false
                            stopEngineAndFinish()
                        },
                    ) {
                        Text("停止引擎并退出")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showExitConfirmDialog = false
                            moveTaskToBack(true)
                        },
                    ) {
                        Text("转入后台")
                    }
                },
            )
        }
    }

    private fun safeCreateWebView(context: Context): View {
        return try {
            createConfiguredWebView(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Android WebView", t)
            DiagLogStore.recordJavaCrash(applicationContext, Thread.currentThread(), t)
            Handler(Looper.getMainLooper()).post {
                refreshEngineLogs()
                handleEngineStateUpdate(
                    EngineState.ERROR,
                    enginePort,
                    "系统 WebView 组件初始化失败 (${t.javaClass.simpleName}): ${t.message ?: "请检查或更新 Android System WebView"}",
                )
            }
            View(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#121318"))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(context: Context): WebView {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(AndroidColor.parseColor("#121318"))
            isFocusable = true
            isFocusableInTouchMode = true

            cookieManager.setAcceptThirdPartyCookies(this, false)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            addJavascriptInterface(exportBridge, "STAndroidBridge")
            webChromeClient = this@MainActivity.webChromeClient

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return false
                    return handleUrlOverride(uri)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val uri = url?.let { Uri.parse(it) } ?: return false
                    return handleUrlOverride(uri)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (engineState == EngineState.READY &&
                        !url.isNullOrBlank() &&
                        !url.contains("/_app/bootstrap") &&
                        !url.startsWith("about:blank")
                    ) {
                        webViewPageLoaded = true
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "未知网络错误"
                        Log.w(TAG, "WebView main frame error ${error?.errorCode}: $desc (${request.url})")
                        if (!desc.contains("ERR_ABORTED", ignoreCase = true)) {
                            refreshEngineLogs()
                            handleEngineStateUpdate(
                                EngineState.ERROR,
                                enginePort,
                                "应用内页面加载失败 (${error?.errorCode}): $desc\n目标地址: ${request.url}",
                            )
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val statusCode = errorResponse?.statusCode ?: 0
                    if (request?.isForMainFrame == true && statusCode >= 400) {
                        val reason = errorResponse?.reasonPhrase ?: ""
                        Log.w(TAG, "WebView main frame HTTP $statusCode: $reason (${request.url})")
                        refreshEngineLogs()
                        handleEngineStateUpdate(
                            EngineState.ERROR,
                            enginePort,
                            "本地 SillyTavern 服务返回异常响应 HTTP $statusCode $reason\n目标地址: ${request.url}",
                        )
                    }
                }
            }

            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                handleFallbackHttpDownload(url, contentDisposition, mimetype)
            }
        }

        webViewRef = wv
        maybeLoadBootstrapIntoWebView()
        return wv
    }

    private fun handleUrlOverride(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        if ((scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost")) {
            return false
        }
        if (scheme == "http" || scheme == "https") {
            pendingExternalUrl = uri.toString()
            return true
        }
        return true
    }

    private fun isLoopbackUrl(rawUrl: String): Boolean {
        return try {
            val uri = Uri.parse(rawUrl)
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""
            (scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost")
        } catch (_: Exception) {
            false
        }
    }

    private fun handleEngineStateUpdate(stateCode: Int, port: Int, detail: String) {
        engineState = stateCode
        enginePort = port
        engineDetail = detail
        try {
            assetVersion = engineBinder?.installedAssetVersion ?: assetVersion
        } catch (_: Exception) {
        }

        when (stateCode) {
            EngineState.READY -> {
                requestNotificationPermissionIfNeeded()
                maybeLoadBootstrapIntoWebView()
            }
            EngineState.ERROR -> {
                webViewPageLoaded = false
                lastLoadedSessionKey = ""
                refreshEngineLogs()
            }
            EngineState.IDLE, EngineState.EXTRACTING, EngineState.STARTING, EngineState.STOPPED -> {
                webViewPageLoaded = false
                if (stateCode == EngineState.STOPPED) {
                    lastLoadedSessionKey = ""
                }
            }
        }
    }

    private fun maybeLoadBootstrapIntoWebView() {
        val wv = webViewRef ?: return
        val binder = engineBinder ?: return
        if (engineState != EngineState.READY || enginePort <= 0) return

        val token = try {
            binder.appToken ?: ""
        } catch (_: Exception) {
            ""
        }
        val sessionKey = "$enginePort:$token"
        if (sessionKey == lastLoadedSessionKey) return

        lastLoadedSessionKey = sessionKey
        webViewPageLoaded = false
        val bootstrapUrl = "http://127.0.0.1:$enginePort/_app/bootstrap"
        val headers = if (token.isNotEmpty()) mapOf("X-App-Token" to token) else emptyMap()
        wv.loadUrl(bootstrapUrl, headers)
    }

    private fun reloadWebViewWithBootstrap() {
        val wv = webViewRef
        if (wv == null || engineState != EngineState.READY || enginePort <= 0) {
            retryEngineStart()
            return
        }
        lastLoadedSessionKey = ""
        maybeLoadBootstrapIntoWebView()
        if (lastLoadedSessionKey.isEmpty()) {
            wv.reload()
        }
    }

    private fun handleFallbackHttpDownload(
        rawUrl: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (rawUrl.isNullOrBlank()) return
        if (!isLoopbackUrl(rawUrl)) {
            showSnack("已拦截非本地下载请求")
            return
        }

        val token = try {
            engineBinder?.appToken ?: ""
        } catch (_: Exception) {
            ""
        }
        val cookieHeader = CookieManager.getInstance().getCookie(rawUrl)

        lifecycleScope.launch {
            val savedResult = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        if (!cookieHeader.isNullOrBlank()) {
                            setRequestProperty("Cookie", cookieHeader)
                        }
                        if (token.isNotEmpty()) {
                            setRequestProperty("X-App-Token", token)
                        }
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            throw IllegalStateException("HTTP $code")
                        }
                        val headerDisposition = conn.getHeaderField("Content-Disposition") ?: contentDisposition
                        val headerMime = conn.contentType?.substringBefore(';')?.trim() ?: mimeType
                        val guessedName = extractFilenameFromDisposition(headerDisposition)
                            ?: URLUtil.guessFileName(rawUrl, headerDisposition, headerMime)

                        conn.inputStream.use { input ->
                            exportRepository.saveStream(guessedName, headerMime, input)
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }

            savedResult.onSuccess { item ->
                exportsList = exportRepository.listExports()
                showSnackWithExportAction("已保存到应用导出箱: ${item.name}")
            }.onFailure { err ->
                Log.e(TAG, "Fallback download failed", err)
                showSnack("下载导出文件失败: ${err.message}")
            }
        }
    }

    private fun extractFilenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=UTF-8''([^;\\r\\n]+)", RegexOption.IGNORE_CASE)
            .find(disposition)
        if (utf8Match != null) {
            return try {
                URLDecoder.decode(utf8Match.groupValues[1].trim().trim('"'), "UTF-8")
            } catch (_: Exception) {
                utf8Match.groupValues[1].trim().trim('"')
            }
        }
        val plainMatch = Regex("filename=[\"']?([^\"';\\r\\n]+)[\"']?", RegexOption.IGNORE_CASE)
            .find(disposition)
        return plainMatch?.groupValues?.getOrNull(1)?.trim()
    }

    private fun handleWebViewBackPress() {
        val wv = webViewRef
        if (wv == null || engineState != EngineState.READY || !webViewPageLoaded) {
            showExitConfirmDialog = true
            return
        }
        wv.evaluateJavascript(
            "(function(){ return Boolean(window.__STAndroidHandleBack && window.__STAndroidHandleBack()); })()",
        ) { result ->
            if (result == "true") {
                return@evaluateJavascript
            }
            if (wv.canGoBack()) {
                wv.goBack()
            } else {
                showExitConfirmDialog = true
            }
        }
    }

    private fun launchSaveToDevice(item: ExportItem) {
        pendingSaveItem = item
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mimeType
            putExtra(Intent.EXTRA_TITLE, item.name)
        }
        createDocumentLauncher.launch(intent)
    }

    private fun saveExportToDeviceUri(item: ExportItem, targetUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { exportRepository.copyToDocumentUri(item, targetUri) }
            }
            result.onSuccess {
                showSnack("已保存到手机存储: ${item.name}")
            }.onFailure { err ->
                showSnack("保存失败: ${err.message}")
            }
        }
    }

    private fun shareExportItem(item: ExportItem) {
        try {
            startActivity(exportRepository.createShareIntent(item))
        } catch (t: Throwable) {
            showSnack("无法拉起系统分享: ${t.message}")
        }
    }

    private fun openInExternalBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            showSnack("无法打开外部浏览器: ${t.message}")
        }
    }

    private fun startStartupWatchdog() {
        startupWatchdogJob?.cancel()
        bootElapsedSeconds = 0
        startupWatchdogJob = lifecycleScope.launch {
            var idleSeconds = 0
            while (engineState != EngineState.READY || !webViewPageLoaded) {
                delay(1_000L)
                bootElapsedSeconds += 1
                if (engineState == EngineState.ERROR || engineState == EngineState.STOPPED) {
                    break
                }
                if (engineState == EngineState.IDLE) {
                    idleSeconds += 1
                    if (idleSeconds >= 8) {
                        DiagLogStore.appendEvent(
                            applicationContext,
                            "Watchdog",
                            "IDLE timeout after 8s (bindRequested=$bindRequested, connected=$serviceConnected)",
                        )
                        unbindEngineSafely()
                        reportEngineProcessFailure(
                            "连接本地后台服务超时（8 秒未收到引擎状态，bindRequested=$bindRequested, connected=$serviceConnected）。",
                        )
                        break
                    }
                } else {
                    idleSeconds = 0
                }
                if (showDiagnosticsDialog || bootElapsedSeconds % 2 == 0) {
                    refreshEngineLogs()
                }
            }
        }
    }

    private fun startEngineServiceIntent(intent: Intent) {
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startService fell back to startForegroundService: ${e.message}")
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start TavernEngineService", t)
                DiagLogStore.appendEvent(
                    applicationContext,
                    "MainActivity",
                    "startForegroundService failed: ${t.javaClass.simpleName}: ${t.message}",
                )
                reportEngineProcessFailure(
                    "无法启动本地后台服务 (${t.javaClass.simpleName}): ${t.message ?: "请检查应用权限"}",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start TavernEngineService", t)
            DiagLogStore.appendEvent(
                applicationContext,
                "MainActivity",
                "startService failed: ${t.javaClass.simpleName}: ${t.message}",
            )
            reportEngineProcessFailure(
                "无法启动本地后台服务 (${t.javaClass.simpleName}): ${t.message}",
            )
        }
    }

    private fun startAndBindEngineService() {
        val boundOk = bindEngineService()
        if (boundOk) {
            val startIntent = Intent(this, TavernEngineService::class.java).apply {
                action = TavernEngineService.ACTION_START
            }
            startEngineServiceIntent(startIntent)
        }
    }

    private fun unbindEngineSafely() {
        val binder = engineBinder
        if (binder != null) {
            try {
                binder.unregisterCallback(engineCallback)
            } catch (_: Throwable) {
            }
        }
        if (bindRequested) {
            try {
                unbindService(serviceConnection)
            } catch (_: Throwable) {
            }
        }
        bindRequested = false
        serviceConnected = false
        engineBinder = null
    }

    private fun rebindEngineServiceConnection() {
        unbindEngineSafely()
        bindEngineService()
    }

    private fun bindEngineService(): Boolean {
        if (bindRequested) return true
        return try {
            val bindIntent = Intent(this, TavernEngineService::class.java)
            val ok = bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            bindRequested = ok
            if (!ok) {
                DiagLogStore.appendEvent(applicationContext, "MainActivity", "bindService returned false")
                reportEngineProcessFailure(
                    "系统拒绝绑定本地引擎服务 (bindService=false)，请检查应用权限后重试。",
                )
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "bindService failed", t)
            DiagLogStore.appendEvent(
                applicationContext,
                "MainActivity",
                "bindService threw ${t.javaClass.simpleName}: ${t.message}",
            )
            reportEngineProcessFailure(
                "绑定本地引擎服务失败 (${t.javaClass.simpleName}): ${t.message}",
            )
            false
        }
    }

    private fun reportEngineProcessFailure(fallbackReason: String) {
        lifecycleScope.launch {
            val liveLogs = withContext(Dispatchers.IO) { fetchLiveBinderLogs() }
            val (headline, fullDiag) = withContext(Dispatchers.IO) {
                val h = DiagLogStore.extractEngineCrashHeadline(applicationContext)
                val d = formatDiagnosticSnapshot(liveLogs)
                h to d
            }
            logsText = fullDiag
            val detailMessage = if (headline.isNotBlank()) {
                "$fallbackReason\n定位摘要: $headline\n请点击下方「复制日志」或「重试启动」。"
            } else {
                "$fallbackReason\n请点击下方「复制日志」或「重试启动」。"
            }
            handleEngineStateUpdate(EngineState.ERROR, 0, detailMessage)

            // ActivityManager sometimes finalizes ApplicationExitInfo 200-400ms after process death.
            delay(450L)
            if (engineState == EngineState.ERROR) {
                val (lateHeadline, lateDiag) = withContext(Dispatchers.IO) {
                    val h = DiagLogStore.extractEngineCrashHeadline(applicationContext)
                    val d = formatDiagnosticSnapshot("")
                    h to d
                }
                if (lateDiag.length >= logsText.length || lateDiag != logsText) {
                    logsText = lateDiag
                }
                if (lateHeadline.isNotBlank() && headline.isBlank()) {
                    engineDetail = "$fallbackReason\n定位摘要: $lateHeadline\n请点击下方「复制日志」或「重试启动」。"
                }
            }
        }
    }

    private fun retryEngineStart() {
        lastLoadedSessionKey = ""
        webViewPageLoaded = false
        logsText = ""
        engineDetail = "正在重新连接本地引擎服务..."
        engineState = EngineState.IDLE
        DiagLogStore.appendEvent(applicationContext, "MainActivity", "User tapped retryEngineStart")
        val binder = engineBinder
        if (binder != null) {
            try {
                val state = binder.currentState
                if (state == EngineState.IDLE || state == EngineState.STOPPED) {
                    binder.startEngine()
                } else {
                    restartEngineFromUi()
                    return
                }
                startStartupWatchdog()
                return
            } catch (_: Exception) {
                unbindEngineSafely()
            }
        } else {
            unbindEngineSafely()
        }
        startAndBindEngineService()
        startStartupWatchdog()
    }

    private fun restartEngineFromUi() {
        lastLoadedSessionKey = ""
        webViewPageLoaded = false
        logsText = ""
        engineDetail = "正在重启本地引擎..."
        engineState = EngineState.STARTING
        DiagLogStore.appendEvent(applicationContext, "MainActivity", "User triggered restartEngineFromUi")
        if (!bindRequested || engineBinder == null) {
            rebindEngineServiceConnection()
        }
        val restartIntent = Intent(this, TavernEngineService::class.java).apply {
            action = TavernEngineService.ACTION_RESTART
        }
        startEngineServiceIntent(restartIntent)
        startStartupWatchdog()
    }

    private fun stopEngineAndFinish() {
        startupWatchdogJob?.cancel()
        try {
            engineBinder?.stopEngine()
        } catch (_: Exception) {
        }
        unbindEngineSafely()
        val stopIntent = Intent(this, TavernEngineService::class.java).apply {
            action = TavernEngineService.ACTION_STOP
        }
        runCatching { startService(stopIntent) }
        finishAndRemoveTask()
    }

    private fun fetchLiveBinderLogs(): String {
        val binder = engineBinder ?: return ""
        return try {
            binder.recentLogs.orEmpty()
        } catch (t: Throwable) {
            "[MainActivity] failed reading binder logs: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun formatDiagnosticSnapshot(liveLogs: String): String {
        val diskReport = DiagLogStore.readCombinedDiagnostics(applicationContext, liveLogs)
        return buildString {
            append(diskReport)
            append("\n--- MainActivity State ---\n")
            append("bindRequested=$bindRequested connected=$serviceConnected engineState=$engineState port=$enginePort\n")
            if (engineDetail.isNotBlank()) {
                append("detail=$engineDetail\n")
            }
        }
    }

    private fun refreshEngineLogs() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val liveLogs = fetchLiveBinderLogs()
                formatDiagnosticSnapshot(liveLogs)
            }
            if (snapshot.isNotBlank()) {
                logsText = snapshot
            }
        }
    }

    private fun copyLogsToClipboard() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val liveLogs = fetchLiveBinderLogs()
                formatDiagnosticSnapshot(liveLogs)
            }
            logsText = snapshot
            val content = snapshot.ifBlank { engineDetail.ifBlank { "No engine logs captured." } }
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SillyTavern Engine Logs", content))
            }
            showSnack("已复制完整诊断日志到剪贴板")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showSnack(message: String) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    private fun showSnackWithExportAction(message: String) {
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "导出箱",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                exportsList = exportRepository.listExports()
                showExportSheet = true
            }
        }
    }
}
