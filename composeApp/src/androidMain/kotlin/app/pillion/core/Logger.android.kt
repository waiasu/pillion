package app.pillion.core

import android.util.Log
import app.pillion.android.DiagnosticFlightRecorder

actual object Logger {
    actual fun d(message: String) { Log.d(TAG, message) }
    actual fun e(message: String, error: Throwable?) { Log.e(TAG, message, error) }
    actual fun trail(message: String) {
        Log.i(TAG, "trail: $message")
        DiagnosticFlightRecorder.record("CORE", message)
    }
    private const val TAG = "Pillion"
}
