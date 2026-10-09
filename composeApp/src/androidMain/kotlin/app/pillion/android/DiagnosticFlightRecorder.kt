package app.pillion.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight, persistent flight recorder for rare field failures.
 *
 * This is deliberately NOT a second logcat. Only low-frequency state transitions and failures are
 * written here. High-rate frame/ACK/OCR diagnostics stay in logcat and are captured on demand by
 * [DiagnosticExporter]. The files live in app-private storage so a process kill/restart does not
 * erase the evidence.
 */
object DiagnosticFlightRecorder {
    private const val DIR_NAME = "diagnostics"
    private const val CURRENT_NAME = "flight-current.log"
    private const val ARCHIVE_PREFIX = "flight-"
    private const val ARCHIVE_SUFFIX = ".log"
    private const val MAX_CURRENT_BYTES = 4L * 1024L * 1024L
    private const val MAX_ARCHIVES = 3 // current + 3 archives = about 16 MiB maximum

    @Volatile private var directory: File? = null
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pillion-flight-writer").apply { isDaemon = true }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        // Pillion also has an SDL router process. Keep one writer owner for the ring files so two
        // processes can never append/rotate the same files concurrently. XMAX/NaviLite runs here.
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n', '\r') }
                .getOrNull()
        }
        if (processName != null && processName != app.packageName) return

        if (directory == null) {
            synchronized(this) {
                if (directory == null) {
                    directory = File(app.filesDir, DIR_NAME).apply { mkdirs() }
                }
            }
        }
        record("APP", "process start pid=${Process.myPid()}")
    }

    /**
     * Non-blocking for the caller. Disk I/O is serialized on one low-traffic daemon writer so an
     * RFCOMM/ACK/control thread never waits for filesystem work.
     */
    fun record(area: String, message: String) {
        if (directory == null) return
        val safeArea = sanitize(area).ifBlank { "GEN" }
        val safeMessage = sanitize(message)
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$wall  +${SystemClock.elapsedRealtime()}ms  [$safeArea] $safeMessage\n"
        runCatching {
            writer.execute { appendLine(line) }
        }
    }

    /**
     * Make a consistent oldest -> newest snapshot without moving/deleting the live ring files.
     * This task is queued behind all breadcrumbs submitted before Export, so the failure that made
     * the user press the button is included. Later breadcrumbs simply queue behind the snapshot.
     */
    fun snapshotTo(output: File) {
        val task = writer.submit {
            output.bufferedWriter(Charsets.UTF_8).use { out ->
                out.appendLine("Pillion persistent flight recorder (oldest -> newest)")
                out.appendLine("Exported: ${Date()}")
                out.appendLine()
                val sources = snapshotFilesOnWriter()
                if (sources.isEmpty()) {
                    out.appendLine("[no persistent flight-log files available]")
                } else {
                    sources.forEach { source ->
                        out.appendLine("===== ${source.name} =====")
                        runCatching {
                            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEach { out.appendLine(it) }
                            }
                        }.onFailure { error ->
                            out.appendLine("[snapshot read failed: ${error.javaClass.simpleName}: ${error.message}]")
                        }
                    }
                }
            }
        }
        // At most ~16 MiB and normally far smaller. This runs only on the Export worker, never on
        // Bluetooth/streaming threads, and keeping it synchronous avoids a half-written snapshot.
        task.get()
    }

    private fun appendLine(line: String) {
        val dir = directory ?: return
        runCatching {
            val current = File(dir, CURRENT_NAME)
            if (current.exists() && current.length() >= MAX_CURRENT_BYTES) rotateOnWriter(dir, current)
            // Open/write/close on the background writer: no buffered tail is held indefinitely.
            current.appendText(line, Charsets.UTF_8)
        }
    }

    private fun snapshotFilesOnWriter(): List<File> {
        val dir = directory ?: return emptyList()
        val archives = dir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith(ARCHIVE_PREFIX) &&
                    it.name.endsWith(ARCHIVE_SUFFIX) && it.name != CURRENT_NAME
            }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        val current = File(dir, CURRENT_NAME).takeIf { it.isFile }
        return archives + listOfNotNull(current)
    }

    private fun rotateOnWriter(dir: File, current: File) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val archive = File(dir, "$ARCHIVE_PREFIX$stamp$ARCHIVE_SUFFIX")
        // Rename is atomic on the same filesystem. If it unexpectedly fails, keep appending to the
        // current file rather than deleting/truncating evidence just to enforce the size cap.
        if (!current.renameTo(archive)) return

        dir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith(ARCHIVE_PREFIX) &&
                    it.name.endsWith(ARCHIVE_SUFFIX) && it.name != CURRENT_NAME
            }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ARCHIVES)
            ?.forEach { old -> runCatching { old.delete() } }
    }

    private fun sanitize(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()
}
