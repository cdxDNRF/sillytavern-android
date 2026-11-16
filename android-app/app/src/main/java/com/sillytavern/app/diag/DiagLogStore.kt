package com.sillytavern.app.diag

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.sillytavern.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-safe persistent diagnostic logger strictly inside app-private filesDir/diag/.
 * Uninstalling the APK removes 100% of these files automatically.
 */
object DiagLogStore {
    private const val DIR_NAME = "diag"
    private const val SERVICE_LOG = "service.log"
    private const val SERVICE_PREV_LOG = "service.prev.log"
    private const val ENGINE_NATIVE_LOG = "engine_native.log"
    private const val ENGINE_NATIVE_PREV_LOG = "engine_native.prev.log"
    private const val NATIVE_CRASH_LOG = "last_native_crash.log"
    private const val NATIVE_CRASH_PREV_LOG = "last_native_crash.prev.log"
    private const val JAVA_CRASH_ENGINE_LOG = "last_java_crash_engine.log"
    private const val JAVA_CRASH_ENGINE_PREV_LOG = "last_java_crash_engine.prev.log"
    private const val JAVA_CRASH_MAIN_LOG = "last_java_crash_main.log"

    private const val MAX_SERVICE_LOG_BYTES = 96L * 1024L
    private const val MAX_SECTION_READ_BYTES = 18 * 1024
    private const val MAX_TRACE_STREAM_BYTES = 16 * 1024

    private val fileLock = Any()

    fun diagDir(context: Context): File {
        return File(context.applicationContext.filesDir, DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun currentProcessName(): String {
        return runCatching { Application.getProcessName() }.getOrNull().orEmpty()
    }

    fun processTag(context: Context): String {
        val proc = currentProcessName()
        return when {
            proc.endsWith(":engine") -> "engine"
            proc.isBlank() || proc == context.packageName -> "main"
            else -> proc.substringAfter(':').replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
    }

    private fun nowStamp(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    fun appendEvent(context: Context, scope: String, message: String) {
        runCatching {
            val dir = diagDir(context)
            val file = File(dir, SERVICE_LOG)
            val tag = processTag(context)
            val pid = Process.myPid()
            val line = "${nowStamp()} [$tag:$pid] [$scope] $message\n"
            synchronized(fileLock) {
                if (file.exists() && file.length() > MAX_SERVICE_LOG_BYTES) {
                    val prev = File(dir, SERVICE_PREV_LOG)
                    prev.delete()
                    file.renameTo(prev)
                }
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
    }

    fun archiveEngineArtifactsForFreshRun(context: Context) {
        runCatching {
            val dir = diagDir(context)
            synchronized(fileLock) {
                rotateIfPresent(File(dir, NATIVE_CRASH_LOG), File(dir, NATIVE_CRASH_PREV_LOG))
                rotateIfPresent(File(dir, JAVA_CRASH_ENGINE_LOG), File(dir, JAVA_CRASH_ENGINE_PREV_LOG))
                rotateIfPresent(File(dir, ENGINE_NATIVE_LOG), File(dir, ENGINE_NATIVE_PREV_LOG))
            }
        }
    }

    private fun rotateIfPresent(src: File, dst: File) {
        if (!src.isFile || src.length() == 0L) {
            src.delete()
            return
        }
        if (dst.exists()) {
            dst.delete()
        }
        if (!src.renameTo(dst)) {
            runCatching {
                src.copyTo(dst, overwrite = true)
            }
            src.delete()
        }
    }

    fun recordJavaCrash(context: Context, thread: Thread, throwable: Throwable) {
        runCatching {
            val dir = diagDir(context)
            val tag = processTag(context)
            val targetName = when (tag) {
                "engine" -> JAVA_CRASH_ENGINE_LOG
                "main" -> JAVA_CRASH_MAIN_LOG
                else -> "last_java_crash_${tag}.log"
            }
            val file = File(dir, targetName)
            val report = buildString {
                append("=== JAVA UNCAUGHT EXCEPTION ($tag) ===\n")
                append("time=${nowStamp()}\n")
                append("process=${currentProcessName()} pid=${Process.myPid()} thread=${thread.name}\n")
                append("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("abis=${Build.SUPPORTED_ABIS.joinToString(",")}\n")
                append("exception=${throwable.javaClass.name}: ${throwable.message}\n")
                append(throwable.stackTraceToString())
                if (!endsWith('\n')) append('\n')
            }
            synchronized(fileLock) {
                FileOutputStream(file, false).use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
            appendEvent(
                context,
                "UncaughtException",
                "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
    }

    fun collectHistoricalExitInfo(context: Context, maxRecords: Int = 8): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "(Android < 11, ApplicationExitInfo unavailable)"
        }
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching "(ActivityManager unavailable)"
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
            if (exits.isEmpty()) {
                return@runCatching "(No historical process exits recorded yet)"
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            buildString {
                exits.forEachIndexed { idx, info ->
                    val ts = fmt.format(Date(info.timestamp))
                    val reasonName = exitReasonName(info.reason)
                    val sigName = signalHint(info.status)
                    append("#${idx + 1} $ts proc=${info.processName} pid=${info.pid} reason=$reasonName(${info.reason}) ")
                    append("status=${info.status}$sigName imp=${info.importance} pss=${info.pss}KB rss=${info.rss}KB\n")
                    if (!info.description.isNullOrBlank()) {
                        append("  desc: ${info.description}\n")
                    }
                    val traceText = readBoundedTraceStream(info)
                    if (traceText.isNotBlank()) {
                        append("  --- OS exit trace ---\n")
                        append(traceText.trimEnd())
                        append('\n')
                    }
                }
            }
        }.getOrElse { "(Failed reading ApplicationExitInfo: ${it.message})" }
    }

    private fun readBoundedTraceStream(info: ApplicationExitInfo): String {
        return runCatching {
            info.traceInputStream?.use { stream ->
                val buf = ByteArray(MAX_TRACE_STREAM_BYTES)
                var total = 0
                while (total < buf.size) {
                    val read = stream.read(buf, total, buf.size - total)
                    if (read <= 0) break
                    total += read
                }
                if (total > 0) String(buf, 0, total, Charsets.UTF_8) else ""
            } ?: ""
        }.getOrDefault("")
    }

    fun extractEngineCrashHeadline(context: Context): String {
        val dir = diagDir(context)
        val nativeCrash = readFileTail(File(dir, NATIVE_CRASH_LOG), 4096)
        if (nativeCrash.isNotBlank()) {
            val lines = nativeCrash.lines().filter { it.isNotBlank() }
            val sigLine = lines.firstOrNull { it.startsWith("signal=") } ?: lines.firstOrNull().orEmpty()
            val milestoneLine = lines.firstOrNull { it.startsWith("last_milestone=") }.orEmpty()
            val pcLine = lines.firstOrNull { it.startsWith("pc_sym=") || it.startsWith("pc=") }.orEmpty()
            return listOf(sigLine, milestoneLine, pcLine)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .ifBlank { "后台引擎触发原生崩溃 (详见下方崩溃日志)" }
        }

        val javaEngineCrash = readFileTail(File(dir, JAVA_CRASH_ENGINE_LOG), 4096)
        if (javaEngineCrash.isNotBlank()) {
            val exLine = javaEngineCrash.lines().firstOrNull { it.startsWith("exception=") }
            if (!exLine.isNullOrBlank()) {
                return "后台进程 Java 异常: ${exLine.removePrefix("exception=")}"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val latestExit = runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                val engineProc = "${context.packageName}:engine"
                am?.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                    ?.firstOrNull { it.processName == engineProc }
            }.getOrNull()
            if (latestExit != null) {
                val reason = exitReasonName(latestExit.reason)
                val sig = signalHint(latestExit.status)
                val desc = latestExit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                return "系统退出记录: ${latestExit.processName} reason=$reason status=${latestExit.status}$sig$desc"
            }
        }
        return ""
    }

    fun readCombinedDiagnostics(
        context: Context,
        extraLiveLogs: String = "",
    ): String {
        val dir = diagDir(context)
        return buildString {
            append("=== SillyTavern Android Diagnostics ===\n")
            append("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ")
            append("device=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})\n")
            append("proc=${currentProcessName()} pid=${Process.myPid()} abis=${Build.SUPPORTED_ABIS.joinToString(",")}\n")

            val nativeCrash = readFileTail(File(dir, NATIVE_CRASH_LOG), MAX_SECTION_READ_BYTES)
            val nativeCrashPrev = readFileTail(File(dir, NATIVE_CRASH_PREV_LOG), 8192)
            if (nativeCrash.isNotBlank()) {
                append("\n--- Native Crash Report (Current) ---\n")
                append(nativeCrash.trimEnd()).append('\n')
            } else if (nativeCrashPrev.isNotBlank()) {
                append("\n--- Native Crash Report (Previous Run) ---\n")
                append(nativeCrashPrev.trimEnd()).append('\n')
            }

            val javaEngineCrash = readFileTail(File(dir, JAVA_CRASH_ENGINE_LOG), MAX_SECTION_READ_BYTES)
            if (javaEngineCrash.isNotBlank()) {
                append("\n--- Engine Java Crash ---\n")
                append(javaEngineCrash.trimEnd()).append('\n')
            }

            val javaMainCrash = readFileTail(File(dir, JAVA_CRASH_MAIN_LOG), 8192)
            if (javaMainCrash.isNotBlank()) {
                append("\n--- UI Java Crash ---\n")
                append(javaMainCrash.trimEnd()).append('\n')
            }

            val exitInfo = collectHistoricalExitInfo(context, maxRecords = 5)
            if (exitInfo.isNotBlank()) {
                append("\n--- OS Historical Process Exits ---\n")
                append(exitInfo.trimEnd()).append('\n')
            }

            val serviceLog = readFileTail(File(dir, SERVICE_LOG), MAX_SECTION_READ_BYTES)
            if (serviceLog.isNotBlank()) {
                append("\n--- Service & Lifecycle Milestones ---\n")
                append(serviceLog.trimEnd()).append('\n')
            }

            val nativeDiskLog = readFileTail(File(dir, ENGINE_NATIVE_LOG), MAX_SECTION_READ_BYTES)
            if (nativeDiskLog.isNotBlank()) {
                append("\n--- Native Runtime Disk Log ---\n")
                append(nativeDiskLog.trimEnd()).append('\n')
            } else {
                val prevNative = readFileTail(File(dir, ENGINE_NATIVE_PREV_LOG), 8192)
                if (prevNative.isNotBlank()) {
                    append("\n--- Native Runtime Disk Log (Previous) ---\n")
                    append(prevNative.trimEnd()).append('\n')
                }
            }

            if (extraLiveLogs.isNotBlank()) {
                append("\n--- Live IPC Engine Buffer ---\n")
                append(extraLiveLogs.takeLast(MAX_SECTION_READ_BYTES).trimEnd()).append('\n')
            }
        }
    }

    private fun readFileTail(file: File, maxBytes: Int): String {
        if (!file.isFile || file.length() <= 0L) return ""
        return runCatching {
            val len = file.length()
            if (len <= maxBytes) {
                file.readText(Charsets.UTF_8)
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    val start = len - maxBytes
                    raf.seek(start)
                    val bytes = ByteArray(maxBytes)
                    val n = raf.read(bytes)
                    if (n <= 0) "" else "...(truncated ${start} bytes)...\n" + String(bytes, 0, n, Charsets.UTF_8)
                }
            }
        }.getOrDefault("")
    }

    private fun exitReasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERM_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "UNKNOWN_$reason"
        }
    }

    private fun signalHint(status: Int): String {
        return when (status) {
            4 -> "(SIGILL)"
            5 -> "(SIGTRAP)"
            6 -> "(SIGABRT)"
            7 -> "(SIGBUS)"
            8 -> "(SIGFPE)"
            9 -> "(SIGKILL)"
            11 -> "(SIGSEGV)"
            13 -> "(SIGPIPE)"
            15 -> "(SIGTERM)"
            else -> ""
        }
    }
}
