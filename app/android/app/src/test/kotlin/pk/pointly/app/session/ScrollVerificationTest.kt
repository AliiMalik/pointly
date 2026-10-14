package pk.pointly.app.session

import org.junit.Assert.assertEquals
import org.junit.Test
import pk.pointly.app.screen.ScreenElement
import pk.pointly.app.screen.TestScreens.el
import pk.pointly.app.screen.TestScreens.snap

class ScrollVerificationTest {

    private val list = el(1, viewId = "recycler_view", cls = "List", scrollable = true, bounds = listOf(0, 294, 1080, 2400))

    private fun page(sliderValue: String, warning: Boolean, shift: Int = 0): List<ScreenElement> = buildList {
        add(list)
        add(el(2, label = "Dark mode settings", clickable = true, bounds = listOf(0, 1072 - shift, 1080, 1316 - shift)))
        add(el(3, text = sliderValue, bounds = listOf(144, 1448 - shift, 936, 1532 - shift)))
        if (warning) add(el(4, text = "HIGH POWER CONSUMPTION", bounds = listOf(569, 1536 - shift, 1008, 1580 - shift)))
        add(el(5, label = "Auto brightness · On", clickable = true, bounds = listOf(0, 1580 - shift, 1080, 1736 - shift)))
        add(el(6, label = "Eye comfort", clickable = true, bounds = listOf(0, 1820 - shift, 1080, 1976 - shift)))
        add(el(7, label = "Auto-rotate · Off", clickable = true, bounds = listOf(0, 2060 - shift, 1080, 2216 - shift)))
    }

    private fun shot(elements: List<ScreenElement>) = snap(*elements.toTypedArray(), pkg = "com.android.settings", windowId = 7)

    private val before = shot(page("501", warning = false))
    private fun verifier() = StepVerifier("scroll", list, before)

    @Test fun liveSliderAndWarningAreNotAScroll() {
        val after = shot(page("872", warning = true))
        assertEquals(StepVerifier.Verdict.UNCHANGED, verifier().judge(after))
    }

    @Test fun rowsMovingUpIsAScroll() {
        val after = shot(page("872", warning = true, shift = 600))
        assertEquals(StepVerifier.Verdict.PROGRESS, verifier().judge(after))
    }

    @Test fun rowsReplacedIsAScroll() {
        val after = shot(listOf(
            list,
            el(8, label = "Font size", clickable = true, bounds = listOf(0, 400, 1080, 560)),
            el(9, label = "Screen resolution", clickable = true, bounds = listOf(0, 560, 1080, 720)),
            el(10, label = "Refresh rate", clickable = true, bounds = listOf(0, 720, 1080, 880)),
        ))
        assertEquals(StepVerifier.Verdict.PROGRESS, verifier().judge(after))
    }

    @Test fun leavingTheWindowCountsAsProgress() {
        val after = snap(el(1, text = "Font size"), pkg = "com.android.settings", windowId = 8)
        assertEquals(StepVerifier.Verdict.PROGRESS, verifier().judge(after))
    }
}
