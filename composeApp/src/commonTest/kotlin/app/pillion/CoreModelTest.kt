package app.pillion

import app.pillion.core.DashMargins
import app.pillion.core.DashResolution
import app.pillion.core.DashScale
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
        assertEquals(70, DashMargins.maxBottom())
        assertEquals(94, DashMargins.clampLeft(95))
        assertEquals(70, DashMargins.clampBottom(99))
        assertEquals(0, DashMargins.clampLeft(-1))
        assertEquals(0, DashMargins.clampBottom(-1))
    }
}
