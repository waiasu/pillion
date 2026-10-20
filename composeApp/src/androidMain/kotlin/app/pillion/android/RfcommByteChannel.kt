package app.pillion.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import app.pillion.core.ByteChannel
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A [ByteChannel] over a Bluetooth RFCOMM (SPP) link to the bonded dash.
 * Single responsibility: move bytes; it owns no protocol knowledge.
 */
class RfcommByteChannel : ByteChannel {
    private val lifecycleLock = Any()
    // A channel is used for one MirrorEngine session only. Stop permanently closes it, including
    // a socket whose BluetoothSocket.connect() has not returned yet.
    @Volatile private var closed = false
    @Volatile private var pendingConnect: FutureTask<Unit>? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connectedName: String? = null
    @Volatile private var lastEvent: String = "created"

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the Activity before start
    override fun open() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
        // A phone may be paired with several "CCU" dashes at once — a real bike (e.g. YCCU_<serial>)
        // AND a dev emulator (YCCU-dev / YCCU-test). Picking just the first match dialed the wrong one
        // (the emulator) and timed out while the bike sat ignored. So try each candidate, real bikes
        // first (dev/test emulators last), and use the first that actually connects.
        val candidates = adapter.bondedDevices
            .filter { d -> d.name?.let { it.startsWith("YCCU") || it.contains("CCU") } == true }
            .sortedBy { d -> val n = d.name?.lowercase().orEmpty(); if ("dev" in n || "test" in n) 1 else 0 }
        if (candidates.isEmpty()) {
            DiagnosticFlightRecorder.record("RFCOMM", "NO_BONDED_DASH")
            error("dash (YCCU…) is not paired")
        }
        // cancelDiscovery() needs BLUETOOTH_SCAN on Android 12+; it's only a connect-speed
        // optimization (nothing is discovering here), so ignore it if the permission is absent.
        runCatching { adapter.cancelDiscovery() }
        var lastError: Throwable? = null
        for (dash in candidates) {
            Log.d("Pillion", "rfcomm: connecting to ${dash.name}")
            DiagnosticFlightRecorder.record("RFCOMM", "CONNECT_START device=${dash.name ?: "unknown"}")
            var candidateSocket: BluetoothSocket? = null
            try {
                val s = dash.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                candidateSocket = s
                // Android's connect() is blocking and may wait indefinitely even after ACL comes
                // back. A FutureTask bounds the caller's wait; closing the pending socket also
                // interrupts the platform operation. Use a bounded shared daemon pool so a vendor
                // stack that ignores close() cannot create unlimited stuck connector threads.
                val task = FutureTask(Callable { s.connect(); Unit })
                synchronized(lifecycleLock) {
                    check(!closed) { "RFCOMM channel already closed" }
                    socket = s // publish BEFORE connect(), so Stop can close it immediately
                    pendingConnect = task
                }
                CONNECT_WORKERS.execute(task)
                try {
                    task.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    lastEvent = "connect:timeout"
                    Log.w("Pillion", "rfcomm: connect timed out after ${CONNECT_TIMEOUT_MS}ms (${dash.name})")
                    DiagnosticFlightRecorder.record(
                        "RFCOMM", "CONNECT_TIMEOUT device=${dash.name ?: "unknown"} timeoutMs=$CONNECT_TIMEOUT_MS",
                    )
                    task.cancel(true)
                    runCatching { s.close() }
                    throw IOException("RFCOMM connect timeout (${CONNECT_TIMEOUT_MS}ms)", e)
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                val connectedInput = s.inputStream
                val connectedOutput = s.outputStream
                synchronized(lifecycleLock) {
                    check(!closed && socket === s) { "RFCOMM connect cancelled" }
                    input = connectedInput
                    output = connectedOutput
                    connectedName = dash.name
                    pendingConnect = null
                    lastEvent = "connected"
                }
                Log.d("Pillion", "rfcomm: connected to ${dash.name}")
                DiagnosticFlightRecorder.record("RFCOMM", "CONNECTED device=${dash.name ?: "unknown"}")
                return
            } catch (t: Throwable) {
                lastError = t
                synchronized(lifecycleLock) {
                    if (socket === candidateSocket) socket = null
                    pendingConnect?.cancel(true)
                    pendingConnect = null
                }
                runCatching { candidateSocket?.close() }
                if (closed) throw IOException("RFCOMM connect cancelled by Stop", t)
                Log.d("Pillion", "rfcomm: ${dash.name} unreachable (${t.message}); trying next")
                DiagnosticFlightRecorder.record(
                    "RFCOMM",
                    "CONNECT_FAIL device=${dash.name ?: "unknown"} ${t.javaClass.simpleName}: ${t.message ?: "-"}",
                )
            }
        }
        throw lastError ?: IllegalStateException("no CCU dash connectable")
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: error("channel not open")
        try {
            out.write(bytes)
            out.flush()
        } catch (t: Throwable) {
            lastEvent = "write:${t.javaClass.simpleName}"
            Log.w("Pillion", "rfcomm: write failed (${t.javaClass.simpleName}: ${t.message})")
            DiagnosticFlightRecorder.record("RFCOMM", "WRITE_FAIL ${t.javaClass.simpleName}: ${t.message ?: "-"}")
            throw t
        }
    }

    override fun read(buffer: ByteArray): Int {
        val result = try {
            (input ?: error("channel not open")).read(buffer)
        } catch (t: Throwable) {
            lastEvent = "read:${t.javaClass.simpleName}"
            Log.w("Pillion", "rfcomm: read failed (${t.javaClass.simpleName}: ${t.message})")
            DiagnosticFlightRecorder.record("RFCOMM", "READ_FAIL ${t.javaClass.simpleName}: ${t.message ?: "-"}")
            throw t
        }
        if (result < 0) {
            lastEvent = "read:eof"
            Log.w("Pillion", "rfcomm: read EOF (-1) from $connectedName")
            DiagnosticFlightRecorder.record("RFCOMM", "READ_EOF device=${connectedName ?: "-"}")
        }
        return result
    }

    override fun close() {
        Log.d("Pillion", "rfcomm: close requested; state=${debugState()}")
        val closingSocket: BluetoothSocket?
        val task: FutureTask<Unit>?
        synchronized(lifecycleLock) {
            closed = true
            closingSocket = socket
            task = pendingConnect
            socket = null
            pendingConnect = null
            input = null
            output = null
            connectedName = null
            lastEvent = "closed"
        }
        // Cancellation releases FutureTask.get() even if a vendor connect() ignores interrupts.
        // Closing the pending socket separately is what normally aborts BluetoothSocket.connect().
        task?.cancel(true)
        runCatching { closingSocket?.close() }
    }

    fun debugState(): String {
        val s = socket
        return "socket=${s != null},isConnected=${runCatching { s?.isConnected }.getOrNull()}," +
            "connecting=${pendingConnect != null},closed=$closed," +
            "input=${input != null},output=${output != null},device=${connectedName ?: "-"},last=$lastEvent"
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private val CONNECT_WORKERS = ThreadPoolExecutor(
            0, 2, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        ) { runnable ->
            Thread(runnable, "Pillion-RFCOMM-connect").apply { isDaemon = true }
        }
        // NaviLite SPP service UUID (0x7220).
        val SPP_UUID: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    }
}
