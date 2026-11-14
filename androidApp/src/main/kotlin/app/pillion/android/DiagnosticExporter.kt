package app.pillion.android

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** User-triggered ZIP. Preserve private breadcrumbs before best-effort ADB queries. */
object DiagnosticExporter {
    private const val LOGCAT_END_MARKER = "__PILLION_LOGCAT_COMPLETE_7B93__"
    private data class LogcatResult(val text: String, val complete: Boolean, val attempts: Int, val reason: String)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pillion-diagnostic-export").apply { isDaemon = true }
    }

    fun exportAsync(context: Context, done: (Result<String>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { export(app) }
            done(result)
        }
    }

    private fun export(context: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val name = "Pillion-diagnostic-$stamp.zip"
        val work = File(context.cacheDir, "diagnostic-$stamp").apply { mkdirs() }
        try {
            DiagnosticFlightRecorder.record("DIAG", "export requested")
            DiagnosticFlightRecorder.snapshot(File(work, "flight.log"))
            File(work, "summary.txt").writeText(
                "Pillion test-base diagnostic\nExported: ${Date()}\n" +
                    "Uptime ms: ${SystemClock.elapsedRealtime()}\n" +
                    "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
            )
            File(work, "settings-backup.json").writeText(AndroidSettingsStore(context).exportBackupJson())
            // ADB is only used on demand. Failure must never destroy the flight snapshot.
            val query = runCatching {
                val adb = PillionAdb.getInstance(context)
                if (!adb.isConnected()) {
                    adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT)
                }
                if (!adb.isConnected()) error("ADB unavailable")
                val task = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "pillion-diagnostic-adb").apply { isDaemon = true }
                }
                try {
                    // ADB runShell() can return a truncated string when its stream closes
                    // prematurely. Only a shell-side trailing marker proves that logcat
                    // actually finished; a final newline alone is not sufficient.
                    var bestPartial: String? = null
                    var failure = "completion marker missing"
                    for (attempt in 1..2) {
                        val future = task.submit<String> {
                            adb.runShell("logcat -d -t 120000 -v threadtime && echo $LOGCAT_END_MARKER")
                        }
                        val output = try {
                            future.get(60, TimeUnit.SECONDS)
                        } catch (t: Exception) {
                            future.cancel(true)
                            failure = "${t.javaClass.simpleName}: ${t.message?.take(120)}"
                            // Do not queue another read on a potentially blocked ADB worker.
                            break
                        }
                        val markerAt = output.lastIndexOf("\n$LOGCAT_END_MARKER")
                        if (markerAt >= 0 &&
                            output.substring(markerAt + 1).trimEnd('\r', '\n') == LOGCAT_END_MARKER
                        ) {
                            return@runCatching LogcatResult(
                                output.substring(0, markerAt + 1), true, attempt,
                                "completion marker received"
                            )
                        }
                        if (output.length > (bestPartial?.length ?: 0)) bestPartial = output
                        failure = "completion marker missing (ADB stream ended early)"
                        if (attempt == 1) {
                            DiagnosticFlightRecorder.record("DIAG", "logcat incomplete; retrying once")
                        }
                    }
                    if (bestPartial != null) {
                        LogcatResult(bestPartial, false, 2, failure)
                    } else error("logcat unavailable: $failure")
                } finally { task.shutdownNow() }
            }
            query.onSuccess { result ->
                File(work, "android-logcat.txt").writeText(result.text)
                File(work, "logcat-status.txt").writeText(
                    "Complete: ${result.complete}\nAttempts: ${result.attempts}\n" +
                        "Reason: ${result.reason}\nBytes: ${result.text.toByteArray().size}\n"
                )
            }.onFailure {
                File(work, "adb-status.txt").writeText(
                    "logcat unavailable: ${it.javaClass.simpleName}: ${it.message?.take(160)}\n"
                )
            }

            val watcherLog = File(context.filesDir, "diagnostics/ccu-watcher.log")
            if (watcherLog.isFile) watcherLog.copyTo(File(work, "ccu-watcher.log"), overwrite = true)
            val bytes = java.io.ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                work.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            if (Build.VERSION.SDK_INT < 29) {
                val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                out.outputStream().use { it.write(bytes.toByteArray()) }
                return out.absolutePath
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create Downloads file")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes.toByteArray()) }
                    ?: error("Cannot open Downloads file")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return name
        } finally {
            work.deleteRecursively()
        }
    }
}
