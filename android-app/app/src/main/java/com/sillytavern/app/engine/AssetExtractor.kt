package com.sillytavern.app.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts immutable SillyTavern runtime bundle (assets/st_app.zip) into app-private
 * filesDir/st_app using SHA-256 atomic directory swaps. User data (st_data/), config
 * (st_config/), and exports (exports/) live in sibling directories and are never modified
 * during installation or app upgrades.
 */
object AssetExtractor {
    private const val TAG = "AssetExtractor"
    private const val ASSET_ZIP = "st_app.zip"
    private const val ASSET_SHA = "st_app.sha256"
    private const val MARKER_FILE = ".installed_sha256"

    data class PreparedPaths(
        val appDir: File,
        val dataRoot: File,
        val configPath: File,
        val cacheDir: File,
        val assetHash: String,
    )

    fun readExpectedSha(context: Context): String {
        return try {
            context.assets.open(ASSET_SHA).bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) {
            ""
        }
    }

    fun readInstalledSha(context: Context): String {
        val marker = File(File(context.filesDir, "st_app"), MARKER_FILE)
        return if (marker.isFile) marker.readText().trim() else ""
    }

    fun ensurePrepared(
        context: Context,
        onStatus: (String) -> Unit = {},
    ): PreparedPaths {
        val filesDir = context.filesDir
        val appDir = File(filesDir, "st_app")
        val stagingDir = File(filesDir, "st_app.staging")
        val backupDir = File(filesDir, "st_app.old")
        val dataRoot = File(filesDir, "st_data").apply { mkdirs() }
        val configDir = File(filesDir, "st_config").apply { mkdirs() }
        val configPath = File(configDir, "config.yaml")
        val engineCacheDir = File(context.cacheDir, "engine_tmp").apply { mkdirs() }

        val expectedSha = readExpectedSha(context)
        val installedSha = readInstalledSha(context)
        val hasEntryPoint = File(appDir, "android-bootstrap.mjs").isFile

        if (hasEntryPoint && expectedSha.isNotEmpty() && expectedSha == installedSha) {
            return PreparedPaths(
                appDir = appDir,
                dataRoot = dataRoot,
                configPath = configPath,
                cacheDir = engineCacheDir,
                assetHash = expectedSha,
            )
        }

        Log.i(TAG, "Extracting SillyTavern runtime (expected=$expectedSha installed=$installedSha)")
        onStatus("正在初始化内置 SillyTavern 运行环境（仅首次或版本更新时执行）...")

        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw IllegalStateException("Cannot create staging directory: ${stagingDir.absolutePath}")
        }

        val stagingCanonical = stagingDir.canonicalPath + File.separator
        val buffer = ByteArray(64 * 1024)
        var fileCount = 0

        context.assets.open(ASSET_ZIP).use { rawStream ->
            ZipInputStream(BufferedInputStream(rawStream, 128 * 1024)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(stagingDir, entry.name)
                    val targetCanonical = target.canonicalPath
                    if (!targetCanonical.startsWith(stagingCanonical) && targetCanonical != stagingDir.canonicalPath) {
                        throw SecurityException("Blocked invalid zip entry path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { out ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                        fileCount++
                        if (fileCount % 1500 == 0) {
                            onStatus("正在部署酒馆内核与依赖文件（已解压 $fileCount 个文件）...")
                        }
                    }
                    zis.closeEntry()
                }
            }
        }

        if (!File(stagingDir, "android-bootstrap.mjs").isFile) {
            throw IllegalStateException("Extracted bundle missing android-bootstrap.mjs")
        }

        val finalHash = if (expectedSha.isNotEmpty()) expectedSha else "extracted-$fileCount"
        File(stagingDir, MARKER_FILE).writeText(finalHash)

        if (appDir.exists()) {
            if (!appDir.renameTo(backupDir)) {
                appDir.deleteRecursively()
            }
        }
        if (!stagingDir.renameTo(appDir)) {
            if (backupDir.exists()) {
                backupDir.renameTo(appDir)
            }
            throw IllegalStateException("Failed to swap extracted runtime directory into place")
        }
        backupDir.deleteRecursively()

        Log.i(TAG, "Successfully deployed $fileCount runtime files into $appDir")
        return PreparedPaths(
            appDir = appDir,
            dataRoot = dataRoot,
            configPath = configPath,
            cacheDir = engineCacheDir,
            assetHash = finalHash,
        )
    }
}
