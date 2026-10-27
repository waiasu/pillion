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

/** Low-frequency, asynchronous, private persistent breadcrumbs. No secrets or frame payloads. */
object DiagnosticFlightRecorder {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val ARCHIVES = 3
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pillion-flight-writer").apply { isDaemon = true }
    }
    @Volatile private var dir: File? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrNull()
        if (process != null && process != app.packageName) return
        dir = File(app.filesDir, "diagnostics").apply { mkdirs() }
        record("APP", "start pid=${Process.myPid()}")
    }

    fun record(area: String, detail: String) {
        if (dir == null) return
        val safeArea = sanitize(area)
        val safeDetail = sanitize(detail)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$date +${SystemClock.elapsedRealtime()}ms [$safeArea] $safeDetail\n"
        runCatching { worker.execute { append(line) } }
    }

    private fun sanitize(value: String) = value.replace(Regex("[\\r\\n\\t]"), " ")
        .take(500)

    private fun append(line: String) {
        val d = dir ?: return
        runCatching {
            val current = File(d, "flight-current.log")
            if (current.length() + line.toByteArray().size > MAX_BYTES) {
                File(d, "flight-${ARCHIVES}.log").delete()
                for (i in ARCHIVES - 1 downTo 1) {
                    File(d, "flight-$i.log").renameTo(File(d, "flight-${i + 1}.log"))
                }
                current.renameTo(File(d, "flight-1.log"))
            }
            current.appendText(line)
        }
    }

    /** Serialize export behind preceding writes; no transport thread waits for disk I/O. */
    fun snapshot(output: File) {
        worker.submit {
            output.bufferedWriter().use { target ->
                val d = dir
                if (d != null) for (i in ARCHIVES downTo 1) {
                    val f = File(d, "flight-$i.log")
                    if (f.exists()) f.forEachLine { target.appendLine(it) }
                }
                d?.let { File(it, "flight-current.log") }?.takeIf { it.exists() }
                    ?.forEachLine { target.appendLine(it) }
            }
        }.get()
    }
}
