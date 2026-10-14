package pk.pointly.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.pointly.app.screen.TestScreens.el
import pk.pointly.app.screen.TestScreens.snap

class ElementOrderingTest {

    private fun raw(text: String, left: Int, top: Int, interactive: Boolean = true) = RawElement(
        text = text, cls = "Button", clickable = interactive, interactive = interactive,
        bounds = listOf(left, top, left + 100, top + 50),
    )

    @Test fun readingOrderUsesRowBuckets() {
        val out = ElementOrdering.finalize(
            listOf(raw("c", 0, 300), raw("b", 500, 100), raw("a", 0, 110)),
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.text })
        assertEquals(listOf(1, 2, 3), out.map { it.id })
    }

    @Test fun capKeepsInteractiveFirst() {
        val text = (0 until 60).map { raw("t$it", 0, it * 10, interactive = false) }
        val buttons = (0 until 60).map { raw("b$it", 0, 2000 + it * 10) }
        val out = ElementOrdering.finalize(text + buttons)
        assertEquals(80, out.size)
        assertEquals(60, out.count { it.text!!.startsWith("b") })
        assertEquals((0 until 20).map { "t$it" }.toSet(), out.filter { it.text!!.startsWith("t") }.map { it.text }.toSet())
    }

    @Test fun signatureIgnoresDigitsAndOrder() {
        val a = listOf(el(1, text = "Chats"), el(2, text = "12:45"), el(3, text = "Sara"))
        val b = listOf(el(1, text = "Sara"), el(2, text = "13:02"), el(3, text = "chats"))
        assertEquals(ElementOrdering.signature("com.whatsapp", a), ElementOrdering.signature("com.whatsapp", b))
    }

    @Test fun signatureDependsOnPackageAndLabels() {
        val a = listOf(el(1, text = "Chats"))
        assertNotEquals(ElementOrdering.signature("com.whatsapp", a), ElementOrdering.signature("com.android.settings", a))
        assertNotEquals(
            ElementOrdering.signature("com.whatsapp", a),
            ElementOrdering.signature("com.whatsapp", listOf(el(1, text = "Calls"))),
        )
    }

    @Test fun targetFoundByViewIdThenLabel() {
        val before = snap(el(1, text = "Display", viewId = "title", clickable = true), pkg = "com.android.settings")
        val sig = TargetSignature.of("com.android.settings", before.elements[0])
        val moved = snap(
            el(1, text = "Network"),
            el(2, text = "Display", viewId = "title", clickable = true, bounds = listOf(0, 900, 1080, 980)),
            pkg = "com.android.settings",
        )
        assertEquals(2, sig.findIn(moved)?.id)
    }

    @Test fun targetWithRepeatedViewIdsUsesLabel() {
        val sig = TargetSignature("com.whatsapp", viewId = "conversations_row", normLabel = "sara · kal milte hain", cls = "View", bounds = listOf(0, 0, 1, 1))
        val s = snap(
            el(1, label = "Ali · Salam", viewId = "conversations_row", clickable = true),
            el(2, label = "Sara · Kal milte hain", viewId = "conversations_row", clickable = true),
            pkg = "com.whatsapp",
        )
        assertEquals(2, sig.findIn(s)?.id)
    }

    @Test fun targetMissingInOtherPackage() {
        val sig = TargetSignature("com.whatsapp", viewId = "fab", normLabel = null, cls = "ImageButton", bounds = listOf(0, 0, 1, 1))
        assertNull(sig.findIn(snap(el(1, viewId = "fab"), pkg = "com.android.settings")))
    }

    @Test fun simpleClassNames() {
        assertEquals("ImageButton", ScreenReader.simpleClass("android.widget.ImageButton", editable = false, scrollable = false))
        assertEquals("Button", ScreenReader.simpleClass("com.google.android.material.button.MaterialButton", editable = false, scrollable = false))
        assertEquals("Switch", ScreenReader.simpleClass("androidx.appcompat.widget.SwitchCompat", editable = false, scrollable = false))
        assertEquals("EditText", ScreenReader.simpleClass("android.view.View", editable = true, scrollable = false))
        assertEquals("List", ScreenReader.simpleClass("androidx.recyclerview.widget.RecyclerView", editable = false, scrollable = true))
        assertEquals("View", ScreenReader.simpleClass("android.widget.FrameLayout", editable = false, scrollable = false))
        assertTrue(ScreenReader.clean("  a\n  b ")!! == "a b")
        assertEquals("Call [number]", ScreenReader.clean("Call 0300 1234567"))
    }
}
