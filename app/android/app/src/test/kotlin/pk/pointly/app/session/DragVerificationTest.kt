package pk.pointly.app.session

import org.junit.Assert.assertEquals
import org.junit.Test
import pk.pointly.app.screen.ScreenElement
import pk.pointly.app.screen.TestScreens.el
import pk.pointly.app.screen.TestScreens.snap

class DragVerificationTest {
    private fun slider(value: Float) = ScreenElement(
        id = 21, text = value.toInt().toString(), cls = "SeekBar", bounds = listOf(120, 1775, 959, 1859),
        range = listOf(0f, 4f, value),
    )

    private fun page(value: Float, sizeLabel: String) = snap(
        el(19, text = "Font size", viewId = "title_font_size", bounds = listOf(120, 1696, 521, 1745)),
        el(20, text = sizeLabel, viewId = "current_font_value", bounds = listOf(557, 1696, 959, 1745)),
        slider(value),
        pkg = "com.android.settings", windowId = 9,
    )

    private val before = page(2f, "Medium")

    @Test fun valueChangeFinishesTheStep() {
        val v = StepVerifier("drag", before.element(21), before)
        assertEquals(StepVerifier.Verdict.PROGRESS, v.judge(page(3f, "Large")))
    }

    @Test fun sameValueKeepsWaiting() {
        val v = StepVerifier("drag", before.element(21), before)
        assertEquals(StepVerifier.Verdict.UNCHANGED, v.judge(page(2f, "Medium")))
    }

    @Test fun clickOnTheTrackIsJudgedByTheValue() {
        val v = StepVerifier("drag", before.element(21), before)
        assertEquals(StepVerifier.Click.UNKNOWN, v.onClick("com.android.settings", null))
        assertEquals(StepVerifier.Verdict.PROGRESS, v.judge(page(4f, "Extra large")))
    }

    private fun shifted(value: Float, dy: Int) = snap(
        el(19, text = "Font size", viewId = "title_font_size", bounds = listOf(120, 1696 + dy, 521, 1745 + dy)),
        ScreenElement(id = 21, text = value.toInt().toString(), cls = "SeekBar", bounds = listOf(120, 1775 + dy, 959, 1859 + dy), range = listOf(0f, 4f, value)),
        pkg = "com.android.settings", windowId = 9,
    )

    @Test fun livePreviewShiftAloneIsNotADrag() {
        val v = StepVerifier("drag", before.element(21), before)
        assertEquals(StepVerifier.Verdict.UNCHANGED, v.judge(shifted(2f, -16)))
    }

    @Test fun shiftedSliderWithNewValueIsADrag() {
        val v = StepVerifier("drag", before.element(21), before)
        assertEquals(StepVerifier.Verdict.PROGRESS, v.judge(shifted(3f, -16)))
    }
}
