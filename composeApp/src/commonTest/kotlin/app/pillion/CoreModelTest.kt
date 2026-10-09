package app.pillion

import app.pillion.core.DashMarginColor
import app.pillion.core.DashMargins
import app.pillion.core.DashResolution
import app.pillion.core.DashScale
import app.pillion.core.MirrorSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModelTest {

    @Test
    fun dash_resolution_follows_usable_area_and_scale() {
        assertEquals(DashResolution(960, 468), DashResolution.DEFAULT)
        assertEquals(DashResolution(800, 400), DashResolution.forLayout(80, 34, DashScale.X20.tenths))
        assertEquals(DashResolution(480, 240), DashResolution.forLayout(80, 34, DashScale.X12.tenths))
    }

    @Test
    fun dash_margin_limits_are_even_and_based_on_output_size() {
        assertEquals(96, DashMargins.maxLeft())
        assertEquals(46, DashMargins.maxBottom())
        assertEquals(94, DashMargins.clampLeft(95))
        assertEquals(46, DashMargins.clampBottom(99))
    }

    @Test
    fun mirror_settings_defaults_are_sane() {
        val s = MirrorSettings()
        assertEquals(60, s.quality)
        assertEquals(3.0, s.maxFps)
        assertEquals(50, s.stickZoomInXPercent)
        assertEquals(25, s.stickZoomInYPercent)
        assertEquals(50, s.stickZoomOutXPercent)
        assertEquals(75, s.stickZoomOutYPercent)
        assertEquals(240, s.dashDpi)
        assertEquals(DashResolution.DEFAULT, s.dashResolution)
        assertEquals(50, s.dashLeftMargin)
        assertEquals(46, s.dashBottomMargin)
        assertEquals(DashMarginColor.Black, s.dashMarginColor)
    }
}
