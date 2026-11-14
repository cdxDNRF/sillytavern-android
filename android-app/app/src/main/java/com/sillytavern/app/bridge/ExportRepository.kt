package com.sillytavern.app.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ExportItem(
    val id: String,
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val mimeType: String,
)

class ExportRepository(private val context: Context) {

    val exportsDir: File
        get() = File(context.filesDir, "exports").apply { mkdirs() }

    fun cleanupPartialFiles() {
        exportsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    fun sanitizeFileName(rawName: String?, mimeType: String?): String {
        val cleaned = (rawName ?: "")
            .replace('\\', '_')
            .replace('/', '_')
            .replace("\u0000", "")
            .trim()
            .trimStart('.')
            .replace(Regex("[:*?\"<>|]"), "_")
            .take(160)

        if (cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") {
            return cleaned
        }

        val ext = when {
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            mimeType?.contains("zip") == true -> "zip"
            mimeType?.contains("json") == true -> "json"
            else -> "dat"
        }
        return "sillytavern-export-${System.currentTimeMillis()}.$ext"
    }

    fun resolveUniqueFile(preferredName: String): File {
        val dir = exportsDir
        val candidate = File(dir, preferredName)
        if (!candidate.exists()) return candidate

        val dotIdx = preferredName.lastIndexOf('.')
        val stem = if (dotIdx > 0) preferredName.substring(0, dotIdx) else preferredName
        val ext = if (dotIdx > 0) preferredName.substring(dotIdx) else ""
        var counter = 2
        while (counter < 10_000) {
            val next = File(dir, "$stem ($counter)$ext")
            if (!next.exists()) return next
            counter++
        }
        return File(dir, "$stem-${UUID.randomUUID().toString().take(8)}$ext")
    }

    fun inferMimeType(fileName: String, hint: String? = null): String {
        val normalizedHint = hint?.trim()?.lowercase()?.takeIf {
            it.isNotEmpty() && it != "application/octet-stream"
        }
        if (normalizedHint != null) return normalizedHint

        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jsonl", "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "zip", "charx", "byaf" -> "application/zip"
            "yaml", "yml", "txt", "md", "settings" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun listExports(): List<ExportItem> {
        val files = exportsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                ExportItem(
                    id = file.absolutePath,
                    name = file.name,
                    file = file,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                    mimeType = inferMimeType(file.name),
                )
            }
    }

    fun saveStream(preferredName: String?, mimeTypeHint: String?, input: InputStream): ExportItem {
        val safeName = sanitizeFileName(preferredName, mimeTypeHint)
        val partFile = File(exportsDir, ".tmp-${UUID.randomUUID()}.part")
        try {
            partFile.outputStream().buffered().use { out ->
                input.copyTo(out)
            }
            val finalFile = resolveUniqueFile(safeName)
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            return ExportItem(
                id = finalFile.absolutePath,
                name = finalFile.name,
                file = finalFile,
                sizeBytes = finalFile.length(),
                lastModifiedMillis = finalFile.lastModified(),
                mimeType = inferMimeType(finalFile.name, mimeTypeHint),
            )
        } finally {
            if (partFile.exists()) {
                partFile.delete()
            }
        }
    }

    fun copyToDocumentUri(item: ExportItem, targetUri: Uri) {
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            item.file.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("无法打开系统目标文件输出流")
    }

    fun createShareIntent(item: ExportItem): Intent {
        val authority = "${context.packageName}.exports.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, item.file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TITLE, item.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "分享 ${item.name}")
    }

    fun deleteExport(item: ExportItem): Boolean {
        return item.file.delete()
    }

    fun clearAll() {
        exportsDir.listFiles()?.forEach { it.delete() }
    }
}
