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
                    val future = task.submit<String> { adb.runShell("logcat -d -t 12000 -v threadtime") }
                    try { future.get(30, TimeUnit.SECONDS) }
                    catch (t: Exception) { future.cancel(true); throw t }
                } finally { task.shutdownNow() }
            }
            query.onSuccess { File(work, "android-logcat.txt").writeText(it) }
                .onFailure {
                    File(work, "adb-status.txt").writeText(
                        "logcat unavailable: ${it.javaClass.simpleName}: ${it.message?.take(160)}\n"
                    )
                }

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
