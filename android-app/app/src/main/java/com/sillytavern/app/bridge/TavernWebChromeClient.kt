package com.sillytavern.app.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class TavernWebChromeClient(
    private val launchFileChooser: (Intent, (Int, Intent?) -> Unit) -> Unit,
    private val onLocalUrlRequested: (String) -> Unit,
    private val onExternalUrlRequested: (String) -> Unit,
) : WebChromeClient() {

    companion object {
        private const val TAG = "SillyTavernWeb"
    }

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    fun cancelPendingFileChooser() {
        pendingCallback?.let { cb ->
            pendingCallback = null
            try {
                cb.onReceiveValue(null)
            } catch (_: Exception) {
            }
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            Log.d(
                TAG,
                "[${consoleMessage.messageLevel()}] ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
            )
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (filePathCallback == null) return false
        cancelPendingFileChooser()
        pendingCallback = filePathCallback

        val acceptTokens = (fileChooserParams?.acceptTypes ?: emptyArray())
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val resolvedMime = resolveDocumentMimeType(acceptTokens)
        val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = resolvedMime
            if (allowMultiple) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        return try {
            launchFileChooser(intent) { resultCode, data ->
                val activeCallback = pendingCallback
                pendingCallback = null
                if (activeCallback == null) return@launchFileChooser

                if (resultCode != Activity.RESULT_OK || data == null) {
                    activeCallback.onReceiveValue(null)
                    return@launchFileChooser
                }

                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { uris.add(it) }
                    }
                } else {
                    data.data?.let { uris.add(it) }
                }

                activeCallback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch document picker", t)
            cancelPendingFileChooser()
            false
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val parentView = view ?: return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

        val tempWebView = try {
            WebView(parentView.context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed creating popup WebView", t)
            return false
        }
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url ?: return true
                routePopupUrl(target)
                parentView.post { tempWebView.destroy() }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    routePopupUrl(Uri.parse(url))
                }
                parentView.post { tempWebView.destroy() }
                return true
            }
        }
        transport.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }

    private fun routePopupUrl(uri: Uri) {
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        if ((scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost")) {
            onLocalUrlRequested(uri.toString())
        } else if (scheme == "http" || scheme == "https") {
            onExternalUrlRequested(uri.toString())
        }
    }

    private fun resolveDocumentMimeType(tokens: List<String>): String {
        if (tokens.isEmpty()) return "*/*"
        val imageExts = setOf("image/*", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg")
        val audioExts = setOf("audio/*", ".mp3", ".wav", ".ogg", ".flac", ".m4a")
        val videoExts = setOf("video/*", ".mp4", ".webm", ".mkv")

        return when {
            tokens.all { it in imageExts || it.startsWith("image/") } -> "image/*"
            tokens.all { it in audioExts || it.startsWith("audio/") } -> "audio/*"
            tokens.all { it in videoExts || it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
    }
}
