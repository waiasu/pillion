package app.pillion.core

import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the dedicated-dash setup and session. */
enum class DashStage {
    /** Not connected — needs pairing (first time) or a reconnect. */
    Idle,
    Pairing,
    Connecting,
    /** Shell bootstrap + helper are ready; the dash can be cast. */
    Connected,
    /** The helper is running and streaming the foreground app to the dash. */
    Casting,
    Error,
}

data class SetupTraceLine(
    val elapsedMs: Long,
    val text: String,
)

data class DashState(
    val stage: DashStage = DashStage.Idle,
    val message: String? = null,
    /** Error recovery can reuse the stored pairing key and does not require another PIN. */
    val canRetrySetup: Boolean = false,
    /** Ephemeral, in-memory setup progress. Never persisted and never contains the pairing PIN. */
    val setupTrace: List<SetupTraceLine> = emptyList(),
)

/**
 * Drives the "dedicated dash display" feature for the UI (onboarding + settings). The UI depends
 * only on this abstraction (DIP); Android provides the implementation backed by the in-app ADB
 * bootstrap, the privileged capture helper, and the foreground service. Platforms that can't offer
 * it (e.g. iOS) simply pass `null` and the feature is hidden.
 *
 * Single responsibility: expose the connect/cast lifecycle as state + intents — it knows nothing
 * about Compose, and the screens know nothing about ADB.
 */
interface DashSetup {
    val state: StateFlow<DashState>

    /** Start pairing service discovery and show the notification used to enter the pairing PIN. */
    fun startPairingAssistant()

    /** End only the one-time pairing assistant/notification; normal dash operation is untouched. */
    fun cancelPairingAssistant()

    /** Open Android's Developer options screen so the user can enable Wireless debugging. */
    fun openWirelessDebuggingSettings()

    /** Re-open Settings from step 2 and refresh/resume the setup notification without losing state. */
    fun reopenWirelessDebuggingSettings()

    /** Programmatic/dev fallback: pair using auto-discovered port or "port code" input. */
    fun pair(code: String)

    /** Programmatic/dev fallback: pair once using an explicit host/port/code. */
    fun pair(host: String, pairingPort: Int, code: String)

    /** Retry the stored-key ADB/helper setup without requesting a new pairing PIN. */
    fun connect()
}
