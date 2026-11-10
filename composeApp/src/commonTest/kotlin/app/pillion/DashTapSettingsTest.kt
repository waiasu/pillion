package app.pillion

import app.pillion.core.DashTapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DashTapSettingsTest {
    @Test fun defaultsMatchXmaxZoomButtons() {
        assertEquals(DashTapSettings(93, 47, 93, 74), DashTapSettings())
    }

    @Test fun percentCoordinatesAreClamped() {
        assertEquals(DashTapSettings(0, 100, 100, 0), DashTapSettings.clamped(-10, 120, 200, -1))
    }

    @Test fun stickInputDefaultsOnAndSurvivesClamping() {
        assertEquals(true, DashTapSettings().enabled)
        assertEquals(false, DashTapSettings.clamped(93, 47, 93, 74, enabled = false).enabled)
    }

    @Test fun markerDurationsCoverTargetFramesPlusTimingMargin() {
        assertEquals(1100, DashTapSettings.markerDurationMs(1))
        assertEquals(600, DashTapSettings.markerDurationMs(2))
        assertEquals(766, DashTapSettings.markerDurationMs(3))
        for (fps in 4..15) assertEquals(600, DashTapSettings.markerDurationMs(fps))
    }
}
