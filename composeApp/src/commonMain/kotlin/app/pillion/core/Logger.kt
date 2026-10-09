package app.pillion.core

/** Minimal multiplatform logger (Android: logcat tag "Pillion"). */
expect object Logger {
    fun d(message: String)
    fun e(message: String, error: Throwable? = null)
    /** Low-frequency persistent field-diagnostic breadcrumb (Android); console-only on iOS. */
    fun trail(message: String)
}
