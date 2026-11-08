package app.pillion.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Immutable snapshot from the Yahoo window on the helper virtual display. */
internal enum class YahooPage { UNKNOWN, NAV, ARRIVED, INITIAL }
internal data class YahooPageSnapshot(
    val page: YahooPage = YahooPage.UNKNOWN,
    val scale: String = "",
    val arrival: String = "",
)

internal object YahooRoadText {
    @Volatile private var observing = false
    @Volatile private var snapshot = YahooPageSnapshot()

    fun isObserving(): Boolean = observing
    fun current(): YahooPageSnapshot = snapshot

    @Synchronized fun observe(enabled: Boolean) {
        observing = enabled
        snapshot = YahooPageSnapshot()
    }

    @Synchronized fun publish(value: YahooPageSnapshot) {
        if (observing) snapshot = value
    }
}

class YahooRoadAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanScheduled = false
    private var lastPage: YahooPage? = null

    private val eventScan = Runnable {
        scanScheduled = false
        scan()
    }
    private val periodicScan = object : Runnable {
        override fun run() {
            if (YahooRoadText.isObserving()) scan()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        DiagnosticFlightRecorder.record("ROAD", "Accessibility service connected")
        handler.post(periodicScan)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!YahooRoadText.isObserving() || event == null) return
        if (event.packageName?.toString() != YAHOO_PACKAGE &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (!scanScheduled) {
            scanScheduled = true
            handler.postDelayed(eventScan, 250L)
        }
    }

    private fun scan() {
        if (!YahooRoadText.isObserving() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val displays = windowsOnAllDisplays
            for (i in 0 until displays.size()) {
                if (displays.keyAt(i) == Display.DEFAULT_DISPLAY) continue
                for (window in displays.valueAt(i).orEmpty()) {
                    val root = window.root ?: continue
                    if (root.packageName?.toString() != YAHOO_PACKAGE) continue
                    // Arrival and the initial screen use Compose views without stable IDs.
                    val texts = ArrayList<String>()
                    collectText(root, texts)
                    val arrivalMessage = texts.firstOrNull { it == ARRIVED_TEXT }
                    val arrival = root.findAccessibilityNodeInfosByViewId(ARRIVAL_ID)
                        .firstOrNull()?.text?.toString().orEmpty()
                    val scale = root.findAccessibilityNodeInfosByViewId(ZOOM_ID)
                        .firstOrNull()?.text?.toString().orEmpty()
                    val page = when {
                        arrivalMessage != null -> YahooPage.ARRIVED
                        arrival.isNotEmpty() -> YahooPage.NAV // includes --:-- while calculating
                        texts.any { it == "検索" } -> YahooPage.INITIAL
                        else -> YahooPage.UNKNOWN
                    }
                    YahooRoadText.publish(YahooPageSnapshot(page, scale, arrival))
                    reportPage(page)
                    return
                }
            }
            YahooRoadText.publish(YahooPageSnapshot())
            reportPage(YahooPage.UNKNOWN)
        } catch (t: Throwable) {
            Log.w(TAG, "Yahoo ROAD accessibility scan failed: ${t.javaClass.simpleName}")
            DiagnosticFlightRecorder.record("ROAD", "Accessibility scan failed: ${t.javaClass.simpleName}")
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    private fun reportPage(page: YahooPage) {
        if (lastPage != page) {
            lastPage = page
            DiagnosticFlightRecorder.record("ROAD", "Yahoo VD page=$page")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(eventScan)
        handler.removeCallbacks(periodicScan)
        YahooRoadText.publish(YahooPageSnapshot())
        DiagnosticFlightRecorder.record("ROAD", "Accessibility service disconnected")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Pillion"
        const val YAHOO_PACKAGE = "jp.co.yahoo.android.apps.navi"
        const val ARRIVED_TEXT = "運転お疲れ様でした"
        const val ZOOM_ID = "jp.co.yahoo.android.apps.navi:id/zoom_text"
        const val ARRIVAL_ID = "jp.co.yahoo.android.apps.navi:id/navi_footer_group_destination_info_switchable_text"
    }
}
