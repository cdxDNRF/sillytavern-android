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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var exportRepository: ExportRepository
    private lateinit var exportBridge: ExportBridge
    private lateinit var webChromeClient: TavernWebChromeClient

    private var engineBinder: ITavernEngine? = null
    private var serviceBound = false

    private var engineState by mutableIntStateOf(EngineState.IDLE)
    private var enginePort by mutableIntStateOf(0)
    private var engineDetail by mutableStateOf("")
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
            val binder = ITavernEngine.Stub.asInterface(service)
            engineBinder = binder
            serviceBound = true
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
            } catch (t: Throwable) {
                Log.e(TAG, "Error querying bound engine service", t)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            serviceBound = false
            webViewPageLoaded = false
            lastLoadedSessionKey = ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        exportRepository = ExportRepository(applicationContext).apply {
            cleanupPartialFiles()
        }
        exportsList = exportRepository.listExports()

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

        requestNotificationPermissionIfNeeded()
        startAndBindEngineService()

        setContent {
            SillyTavernAppTheme {
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        exportsList = exportRepository.listExports()
        if (!serviceBound) {
            bindEngineService()
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
        webChromeClient.cancelPendingFileChooser()
        exportBridge.releaseAll()
        if (serviceBound) {
            try {
                engineBinder?.unregisterCallback(engineCallback)
            } catch (_: Exception) {
            }
            unbindService(serviceConnection)
            serviceBound = false
        }
        webViewRef?.let { wv ->
            wv.stopLoading()
            wv.removeJavascriptInterface("STAndroidBridge")
            wv.destroy()
        }
        webViewRef = null
        super.onDestroy()
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
            AndroidView(
                factory = { ctx -> createConfiguredWebView(ctx) },
                modifier = Modifier.fillMaxSize(),
            )

            AnimatedVisibility(
                visible = engineState != EngineState.READY || !webViewPageLoaded,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BootOverlay(
                    state = engineState,
                    statusDetail = engineDetail,
                    logsPreview = logsText,
                    onRetry = { retryEngineStart() },
                    onCopyLogs = { copyLogsToClipboard() },
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
                    if (engineState == EngineState.READY && !url.isNullOrBlank() && !url.endsWith("/_app/bootstrap")) {
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
                        Log.w(TAG, "WebView main frame error ${error?.errorCode}: ${error?.description}")
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
                maybeLoadBootstrapIntoWebView()
            }
            EngineState.ERROR -> {
                webViewPageLoaded = false
                refreshEngineLogs()
            }
            EngineState.IDLE, EngineState.EXTRACTING, EngineState.STARTING, EngineState.STOPPED -> {
                if (stateCode != EngineState.READY) {
                    webViewPageLoaded = false
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
        val wv = webViewRef ?: return
        if (engineState != EngineState.READY || enginePort <= 0) {
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

    private fun startAndBindEngineService() {
        val startIntent = Intent(this, TavernEngineService::class.java).apply {
            action = TavernEngineService.ACTION_START
        }
        ContextCompat.startForegroundService(this, startIntent)
        bindEngineService()
    }

    private fun bindEngineService() {
        val bindIntent = Intent(this, TavernEngineService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun retryEngineStart() {
        lastLoadedSessionKey = ""
        webViewPageLoaded = false
        val binder = engineBinder
        if (binder != null) {
            try {
                binder.startEngine()
                return
            } catch (_: Exception) {
            }
        }
        startAndBindEngineService()
    }

    private fun restartEngineFromUi() {
        lastLoadedSessionKey = ""
        webViewPageLoaded = false
        val restartIntent = Intent(this, TavernEngineService::class.java).apply {
            action = TavernEngineService.ACTION_RESTART
        }
        ContextCompat.startForegroundService(this, restartIntent)
        if (!serviceBound) {
            bindEngineService()
        }
    }

    private fun stopEngineAndFinish() {
        try {
            engineBinder?.stopEngine()
        } catch (_: Exception) {
        }
        val stopIntent = Intent(this, TavernEngineService::class.java).apply {
            action = TavernEngineService.ACTION_STOP
        }
        startService(stopIntent)
        finishAndRemoveTask()
    }

    private fun refreshEngineLogs() {
        try {
            logsText = engineBinder?.recentLogs ?: logsText
        } catch (t: Throwable) {
            logsText = "获取日志失败: ${t.message}"
        }
    }

    private fun copyLogsToClipboard() {
        refreshEngineLogs()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val content = logsText.ifBlank { engineDetail.ifBlank { "No engine logs captured." } }
        cm.setPrimaryClip(ClipData.newPlainText("SillyTavern Engine Logs", content))
        showSnack("已复制引擎日志到剪贴板")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
