package app.pillion.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import app.pillion.core.AppInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    // Diagnostics favor readable evidence over speed; each command retains a hang safeguard.
    private const val COMMAND_TIMEOUT_MS = 45_000L
    private const val LOGCAT_TIMEOUT_MS = 60_000L
    private const val MAX_CAPTURE_CHARS = 12_000_000

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
                // Keep an independent record of ADB open/read/completion progress. Unlike logcat,
                // this trace is written by Pillion itself even when ADB stalls or drops the stream.
                try {
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
                        LOGCAT_TIMEOUT_MS,
                    ),
                )
                writeText(
                    File(workDir, "system-state.txt"),
                    buildSystemState(adb),
                )
                } finally {
                    writeText(File(workDir, "adb-export-trace.txt"), adb.report())
                }

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

    /**
     * Export-only ADB reader. The shell completion marker is checked DURING each read, because
     * libadb may keep the stream open long after the last command output was delivered. Tracing is
     * kept in app memory, not logcat/ADB, and is written into the same diagnostic ZIP.
     */
    private class AdbSnapshot(private val context: Context) {
        private val adb by lazy { PillionAdb.getInstance(context) }
        private val startedMs = SystemClock.elapsedRealtime()
        private val trace = StringBuilder("Pillion ADB export trace\n")
        private var sequence = 0
        private var transportStalled = false
        private var adbChecked = false

        // Export-only recovery. A surviving Helper can be reused after Pillion's own process
        // restarts without restoring this process's libadb connection.
        // Never restart adbd or touch the Helper/VD just to collect diagnostics.
        private fun ensureExportAdbConnection() {
            if (adbChecked) return
            adbChecked = true
            val start = SystemClock.elapsedRealtime()
            if (adb.isConnected()) {
                trace.appendLine("export-adb status=already-connected")
                return
            }
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "pillion-diag-adb-reconnect").apply { isDaemon = true }
            }
            try {
                val task = executor.submit<Boolean> {
                    adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT)
                }
                try {
                    val result = task.get(5_000L, TimeUnit.MILLISECONDS)
                    trace.appendLine(
                        "export-adb status=${if (adb.isConnected()) "connected" else "unavailable"} " +
                            "connectReturn=$result elapsedMs=${SystemClock.elapsedRealtime() - start}"
                    )
                } catch (_: TimeoutException) {
                    task.cancel(true)
                    trace.appendLine("export-adb status=timeout elapsedMs=${SystemClock.elapsedRealtime() - start}")
                } catch (e: ExecutionException) {
                    trace.appendLine("export-adb status=failed type=${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
                } catch (_: InterruptedException) {
                    task.cancel(true)
                    Thread.currentThread().interrupt()
                    trace.appendLine("export-adb status=interrupted")
                }
            } finally {
                executor.shutdownNow()
            }
        }

        private class Progress {
            val phase = AtomicReference("not-started")
            val stream = AtomicReference<io.github.muntashirakon.adb.AdbStream?>(null)
            val output = StringBuffer()
            val chars = AtomicLong(0L)
            val firstReadMs = AtomicLong(0L)
            val lastReadMs = AtomicLong(0L)
        }

        private data class ReadResult(val output: String, val status: String, val complete: Boolean)

        fun report(): String = trace.toString()

        fun capture(
            label: String,
            command: String,
            timeoutMs: Long = COMMAND_TIMEOUT_MS,
        ): String {
            ensureExportAdbConnection()
            sequence++
            val start = SystemClock.elapsedRealtime()
            val marker = "__PILLION_DIAG_DONE_${startedMs}_${sequence}__"
            if (transportStalled) {
                trace.appendLine("command=$label status=skipped cause=earlier_unresponsive_ADB")
                return "[SKIPPED $label: earlier ADB stream would not close]\n"
            }
            val progress = Progress()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "pillion-diag-${sequence}").apply { isDaemon = true }
            }
            val wrapped = command + "; printf '\n${marker}:%s\n' \"$?\""
            val task = executor.submit<ReadResult> { readCommand(wrapped, marker, progress) }
            var result: ReadResult? = null
            var status = "unknown"
            try {
                result = task.get(timeoutMs, TimeUnit.MILLISECONDS)
                status = result.status
            } catch (_: TimeoutException) {
                status = "timeout"
                // Close only the diagnostic stream; never reset the shared ADB manager/adbd/Helper.
                progress.phase.set("timeout-close-requested")
                Thread {
                    runCatching { progress.stream.get()?.close() }
                }.apply { name = "pillion-diag-close"; isDaemon = true; start() }
                try {
                    result = task.get(1_500L, TimeUnit.MILLISECONDS)
                    status = "timeout-stream-unblocked(${result.status})"
                } catch (_: TimeoutException) {
                    transportStalled = true
                    status = "timeout-worker-still-blocked"
                } catch (e: Exception) {
                    status = "timeout-worker-failed:${e.javaClass.simpleName}"
                }
                task.cancel(true)
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                status = "failed:${cause.javaClass.simpleName}:${cause.message}"
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                transportStalled = true
                status = "interrupted"
                task.cancel(true)
            } finally {
                executor.shutdownNow()
                val end = SystemClock.elapsedRealtime()
                val first = progress.firstReadMs.get()
                val last = progress.lastReadMs.get()
                trace.appendLine(
                    "command=$label status=$status elapsedMs=${end - start} " +
                        "phase=${progress.phase.get()} chars=${progress.chars.get()} " +
                        "firstDataMs=${if (first > 0) first - start else -1} " +
                        "lastDataMs=${if (last > 0) last - start else -1}"
                )
            }

            val output = result?.output ?: progress.output.toString()
            return if (result?.complete == true && status.startsWith("complete")) {
                output.ifBlank { "[command returned no output: $label]\n" }
            } else {
                output + "\n[INCOMPLETE $label: $status; see adb-export-trace.txt]\n"
            }
        }

        private fun readCommand(
            command: String,
            marker: String,
            progress: Progress,
        ): ReadResult {
            progress.phase.set("opening-adb-stream")
            val stream = adb.openShellStream(command)
            progress.stream.set(stream)
            try {
                progress.phase.set("opening-input-reader")
                val reader = stream.openInputStream().bufferedReader()
                progress.phase.set("reading")
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                var ending = "unknown"
                while (true) {
                    val read = try {
                        reader.read(buffer)
                    } catch (e: IOException) {
                        ending = if (e.message?.contains("Stream closed", ignoreCase = true) == true) {
                            "stream-closed-before-marker"
                        } else {
                            "IOException:${e.message}"
                        }
                        break
                    }
                    if (read < 0) {
                        ending = "EOF-before-marker"
                        break
                    }
                    if (read == 0) continue
                    val now = SystemClock.elapsedRealtime()
                    progress.firstReadMs.compareAndSet(0L, now)
                    progress.lastReadMs.set(now)
                    val oldLength = progress.output.length
                    progress.output.append(buffer, 0, read)
                    progress.chars.addAndGet(read.toLong())
                    // Only scan the new chunk plus the possible marker split over its boundary.
                    val from = maxOf(0, oldLength - marker.length - 4)
                    val found = progress.output.indexOf("\n$marker:", from)
                    if (found >= 0) {
                        val eol = progress.output.indexOf("\n", found + 1)
                        if (eol >= 0) {
                            val exitCode = progress.output.substring(found + marker.length + 2, eol).trim()
                            progress.phase.set("marker-received")
                            return ReadResult(
                                progress.output.substring(0, found),
                                "complete(exit=$exitCode)",
                                exitCode == "0",
                            )
                        }
                    }
                    if (progress.output.length >= MAX_CAPTURE_CHARS) {
                        ending = "size-limit-${MAX_CAPTURE_CHARS}"
                        break
                    }
                }
                progress.phase.set(ending)
                return ReadResult(progress.output.toString(), ending, false)
            } finally {
                progress.stream.compareAndSet(stream, null)
                runCatching { stream.close() }
            }
        }
    }

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
