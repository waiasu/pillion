package app.pillion.server

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Tiny shell-UID helper used only when the normal DashServer helper is not alive during
 * Save & Restart / Reset & Restart.
 *
 * It is launched by app_process through the already-established ADB connection, exposes a
 * loopback-only READY/command socket, then performs the package force-stop + relaunch itself.
 * Nothing here creates a virtual display or touches normal DashServer state.
 */
object RestartHelper {
    const val PORT = 28118

    private const val PACKAGE_NAME = "app.pillion"
    private const val COMPONENT_NAME = "app.pillion/app.pillion.MainActivity"
    private const val IDLE_TIMEOUT_MS = 15_000
    private const val SOCKET_TIMEOUT_MS = 2_000
    private const val FORCE_STOP_DELAY_MS = 1_000L
    private const val RELAUNCH_DELAY_MS = 1_000L

    private const val ACTION_NONE = 0
    private const val ACTION_RESTART = 1
    private const val ACTION_QUIT = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.soTimeout = IDLE_TIMEOUT_MS
            server.bind(InetSocketAddress("127.0.0.1", PORT))

            while (true) {
                val action = try {
                    server.accept().use { socket ->
                        socket.soTimeout = SOCKET_TIMEOUT_MS
                        val request = socket.getInputStream().bufferedReader().readLine()
                        val writer = socket.getOutputStream().bufferedWriter()
                        when (request) {
                            "PING" -> {
                                writer.write("READY\n")
                                writer.flush()
                                ACTION_NONE
                            }
                            "RESTART_PILLION" -> {
                                // ACK before killing Pillion so the caller knows the request reached
                                // an independent shell-UID process.
                                writer.write("OK\n")
                                writer.flush()
                                ACTION_RESTART
                            }
                            "QUIT" -> {
                                writer.write("OK\n")
                                writer.flush()
                                ACTION_QUIT
                            }
                            else -> {
                                writer.write("ERR\n")
                                writer.flush()
                                ACTION_NONE
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    ACTION_QUIT
                }

                when (action) {
                    ACTION_RESTART -> break
                    ACTION_QUIT -> return
                }
            }
        } finally {
            runCatching { server.close() }
        }

        // Keep the same timing as the already-proven DashServer restart path, but execute the
        // commands from this independent app_process helper instead of a detached sleeping shell.
        Thread.sleep(FORCE_STOP_DELAY_MS)
        runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("am", "force-stop", PACKAGE_NAME))
                .waitFor()
        }

        Thread.sleep(RELAUNCH_DELAY_MS)
        runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("am", "start", "-n", COMPONENT_NAME))
                .waitFor()
        }
    }
}
