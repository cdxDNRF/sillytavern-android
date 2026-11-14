package com.sillytavern.app.bridge

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ExportBridge(
    private val repository: ExportRepository,
    private val onExportCompleted: (ExportItem) -> Unit,
    private val onExportFailed: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ExportBridge"
        private const val MAX_ACTIVE_EXPORTS = 8
    }

    private data class ActiveStream(
        val finalName: String,
        val mimeType: String,
        val partFile: File,
        val output: BufferedOutputStream,
        var writtenBytes: Long = 0L,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streams = ConcurrentHashMap<String, ActiveStream>()

    @JavascriptInterface
    fun beginExport(filename: String?, mimeType: String?, totalBytes: Long): String {
        if (streams.size >= MAX_ACTIVE_EXPORTS) {
            return ""
        }
        val resolvedMime = repository.inferMimeType(filename ?: "", mimeType)
        val safeName = repository.sanitizeFileName(filename, resolvedMime)
        val exportId = UUID.randomUUID().toString()
        val partFile = File(repository.exportsDir, ".$exportId.part")

        return try {
            val out = BufferedOutputStream(FileOutputStream(partFile), 64 * 1024)
            streams[exportId] = ActiveStream(
                finalName = safeName,
                mimeType = resolvedMime,
                partFile = partFile,
                output = out,
            )
            exportId
        } catch (t: Throwable) {
            Log.e(TAG, "beginExport failed", t)
            partFile.delete()
            mainHandler.post { onExportFailed("准备导出文件失败: ${t.message}") }
            ""
        }
    }

    @JavascriptInterface
    fun appendExportChunk(exportId: String?, base64Data: String?) {
        if (exportId.isNullOrEmpty() || base64Data.isNullOrEmpty()) return
        val stream = streams[exportId] ?: return
        synchronized(stream) {
            val decoded = Base64.decode(base64Data, Base64.DEFAULT)
            if (decoded.isNotEmpty()) {
                stream.output.write(decoded)
                stream.writtenBytes += decoded.size
            }
        }
    }

    @JavascriptInterface
    fun finishExport(exportId: String?) {
        if (exportId.isNullOrEmpty()) return
        val stream = streams.remove(exportId) ?: return
        try {
            synchronized(stream) {
                stream.output.flush()
                stream.output.close()
            }
            val finalFile = repository.resolveUniqueFile(stream.finalName)
            if (!stream.partFile.renameTo(finalFile)) {
                stream.partFile.copyTo(finalFile, overwrite = true)
                stream.partFile.delete()
            }
            val item = ExportItem(
                id = finalFile.absolutePath,
                name = finalFile.name,
                file = finalFile,
                sizeBytes = finalFile.length(),
                lastModifiedMillis = finalFile.lastModified(),
                mimeType = repository.inferMimeType(finalFile.name, stream.mimeType),
            )
            mainHandler.post { onExportCompleted(item) }
        } catch (t: Throwable) {
            Log.e(TAG, "finishExport failed", t)
            stream.partFile.delete()
            mainHandler.post { onExportFailed("写入导出文件失败: ${t.message}") }
        }
    }

    @JavascriptInterface
    fun abortExport(exportId: String?, reason: String?) {
        if (exportId.isNullOrEmpty()) return
        val stream = streams.remove(exportId) ?: return
        try {
            synchronized(stream) {
                stream.output.close()
            }
        } catch (_: Exception) {
        }
        stream.partFile.delete()
        if (!reason.isNullOrBlank()) {
            mainHandler.post { onExportFailed("导出已取消: $reason") }
        }
    }

    fun releaseAll() {
        streams.keys.toList().forEach { id ->
            abortExport(id, null)
        }
    }
}
