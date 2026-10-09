package app.pillion.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.pillion.core.AppInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-demand field-diagnostic exporter.
 *
 * The persistent flight log is snapshotted first, before any ADB/system query. Every later section
 * is best-effort: a dead ADB connection or a failed dumpsys must never invalidate the evidence that
 * was already captured. Full-device logcat/dumpsys are collected only while the user presses Export.
 */
object DiagnosticExporter {
    private const val LOGCAT_LINES = 12_000

    fun export(context: Context): Result<String> {
        val app = context.applicationContext
        DiagnosticFlightRecorder.record("DIAG", "manual export requested")

        // Capture cheap in-process state immediately, before any file copy/dumpsys can give a
        // naturally recovering Bluetooth session time to change underneath us.
        val instantSummary = buildSummary(app)
        val instantBluetooth = buildBluetoothSummary()

        val result = runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val workDir = File(app.cacheDir, "diagnostic-$stamp-${System.nanoTime()}").apply { mkdirs() }

            try {
                // Preserve the long-term Pillion evidence before running any system query.
                snapshotFlightLogs(workDir)
                writeText(File(workDir, "summary.txt"), instantSummary)

                val adb = AdbSnapshot(app)
                // Bluetooth is the primary target of this field recorder. Take its live dumpsys
                // before logcat/display queries so the state is as close to button-press time as possible.
                writeText(
                    File(workDir, "bluetooth-state.txt"),
                    buildString {
                        append(instantBluetooth)
                        append("\n\n===== dumpsys bluetooth_manager =====\n")
                        append(adb.capture("dumpsys bluetooth_manager", "dumpsys bluetooth_manager"))
                    },
                )
                writeText(
                    File(workDir, "android-logcat.txt"),
                    adb.capture(
                        "logcat",
                        "logcat -d -v threadtime -b main -b system -b crash -b events -t $LOGCAT_LINES",
                    ),
                )
                writeText(
                    File(workDir, "system-state.txt"),
                    buildSystemState(adb),
                )

                val zip = File(app.cacheDir, "Pillion-diagnostic-$stamp.zip")
                zipDirectory(workDir, zip)
                val location = saveToDownloads(app, zip)
                DiagnosticFlightRecorder.record("DIAG", "manual export saved: $location")
                location
            } finally {
                runCatching { workDir.deleteRecursively() }
            }
        }
        result.onFailure { error ->
            DiagnosticFlightRecorder.record(
                "DIAG",
                "manual export FAILED ${error.javaClass.simpleName}: ${error.message ?: "-"}",
            )
        }
        return result
    }

    private fun snapshotFlightLogs(workDir: File) {
        val out = File(workDir, "pillion-flight.log")
        runCatching { DiagnosticFlightRecorder.snapshotTo(out) }
            .onFailure { error ->
                // Never touch the original ring on failure. Leave a clear marker in the export and
                // continue collecting the remaining best-effort snapshots.
                runCatching {
                    out.writeText(
                        "[flight snapshot failed: ${error.javaClass.simpleName}: ${error.message}]\n",
                        Charsets.UTF_8,
                    )
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun buildBluetoothSummary(): String = buildString {
        appendLine("===== Android Bluetooth API snapshot =====")
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null) {
            appendLine("adapter=unavailable")
            return@buildString
        }
        appendLine("adapterState=${runCatching { adapter.state }.getOrNull()}")
        appendLine("adapterEnabled=${runCatching { adapter.isEnabled }.getOrNull()}")
        val bonded = runCatching { adapter.bondedDevices.toList() }.getOrNull()
        if (bonded == null) {
            appendLine("bondedDevices=[unavailable]")
        } else {
            appendLine("bondedDevices=${bonded.size}")
            bonded.sortedBy { runCatching { it.name }.getOrNull().orEmpty() }.forEach { device ->
                val name = runCatching { device.name }.getOrNull() ?: "unknown"
                val bondState = runCatching { device.bondState }.getOrNull()
                appendLine("  name=$name bondState=$bondState")
            }
        }
    }

    private fun buildSummary(context: Context): String = buildString {
        val settings = AndroidSettingsStore(context)
        appendLine("Pillion diagnostic snapshot")
        appendLine("exportTime=${Date()}")
        appendLine("pillionVersion=${AppInfo.VERSION}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("build=${Build.DISPLAY}")
        appendLine("uptimeMs=${android.os.SystemClock.elapsedRealtime()}")
        appendLine("captureState=${CaptureService.state.value}")
        appendLine()
        appendLine("===== Saved Pillion settings =====")
        appendLine("dashEnabled=${settings.dashEnabled()}")
        appendLine("imageQuality=${settings.imageQuality()}")
        appendLine("maxFps=${settings.maxFps()}")
        appendLine("dashDpi=${settings.dashDpi()}")
        appendLine("dashScaleTenths=${settings.dashScaleTenths()}")
        appendLine("leftMargin=${settings.dashLeftMargin()}")
        appendLine("bottomMargin=${settings.dashBottomMargin()}")
        appendLine("ocrEnabled=${settings.ocrEnabled()}")
        appendLine("restartAppOnVd=${settings.restartAppOnVd()}")
        appendLine("fixedDashAppEnabled=${settings.fixedDashAppEnabled()}")
        appendLine("fixedDashAppPackage=${settings.fixedDashAppPackage() ?: "-"}")
    }

    private fun buildSystemState(adb: AdbSnapshot): String = buildString {
        section(this, "Pillion services", adb.capture("activity services", "dumpsys activity services app.pillion"))
        section(this, "Pillion processes", adb.capture("activity processes", "dumpsys activity processes app.pillion"))
        section(this, "Process list", adb.capture("ps", "ps -A"))
        section(this, "Displays", adb.capture("display", "dumpsys display"))
        section(this, "MediaProjection", adb.capture("media_projection", "dumpsys media_projection"))
        section(
            this,
            "Relevant activities/tasks",
            adb.capture(
                "activity activities",
                "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp|displayId=|app.pillion|Task\\{' | head -n 600",
            ),
        )
    }

    private fun section(builder: StringBuilder, title: String, body: String) {
        builder.append("\n===== ").append(title).append(" =====\n")
        builder.append(body)
        if (!body.endsWith('\n')) builder.append('\n')
    }

    private class AdbSnapshot(private val context: Context) {
        private val adb by lazy { PillionAdb.getInstance(context) }
        @Volatile private var reconnectTried = false

        fun capture(label: String, command: String): String {
            fun run(): Result<String> = runCatching { runBounded(command, COMMAND_TIMEOUT_MS) }

            var result = run()
            if (result.isFailure && !reconnectTried) {
                reconnectTried = true
                // Best-effort loopback reconnect only. Do not invoke helper setup, pairing, tcpip,
                // privilege changes or any other recovery action during evidence collection.
                runCatching { adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT) }
                result = run()
            }
            return result.fold(
                onSuccess = { output -> output.ifBlank { "[command returned no output]\n" } },
                onFailure = { error ->
                    "[FAILED $label: ${error.javaClass.simpleName}: ${error.message}]\n"
                },
            )
        }

        /** Close the ADB stream from a daemon timer if a diagnostic command stalls. */
        private fun runBounded(command: String, timeoutMs: Long): String {
            val stream = adb.openShellStream(command)
            val timedOut = AtomicBoolean(false)
            val closer = Thread({
                try {
                    Thread.sleep(timeoutMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                timedOut.set(true)
                runCatching { stream.close() }
            }, "pillion-diag-timeout").apply { isDaemon = true }
            closer.start()

            val output = StringBuilder()
            try {
                val reader = stream.openInputStream().bufferedReader()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = try {
                        reader.read(buffer)
                    } catch (t: Throwable) {
                        if (timedOut.get()) break else throw t
                    }
                    if (read < 0) break
                    output.append(buffer, 0, read)
                }
            } finally {
                closer.interrupt()
                runCatching { stream.close() }
            }
            if (timedOut.get()) {
                output.append("\n[TIMEOUT after ").append(timeoutMs).append(" ms]\n")
            }
            return output.toString()
        }
    }

    private const val COMMAND_TIMEOUT_MS = 12_000L

    private fun writeText(file: File, text: String) {
        runCatching { file.writeText(text, Charsets.UTF_8) }
            .getOrElse { error ->
                file.writeText("[write failed: ${error.javaClass.simpleName}: ${error.message}]\n", Charsets.UTF_8)
            }
    }

    private fun zipDirectory(directory: File, output: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun saveToDownloads(context: Context, source: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pillion")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create Downloads entry")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Cannot open Downloads output")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                val updated = resolver.update(uri, values, null, null)
                check(updated > 0) { "Cannot publish Downloads entry" }
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            runCatching { source.delete() }
            "Downloads/Pillion/${source.name}"
        } else {
            // Legacy fallback without requesting broad storage permission. Modern XMAX/Pillion
            // devices use the MediaStore path above; this keeps API 24-28 builds functional.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Pillion").apply { mkdirs() }
            val target = File(dir, source.name)
            source.copyTo(target, overwrite = true)
            runCatching { source.delete() }
            target.absolutePath
        }
    }
}
