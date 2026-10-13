package pk.rehnuma.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskingTest {
    private fun m(s: String) = Masking.mask(s)

    @Test fun cnic() = assertEquals("[number]", m("35202-1234567-1"))

    @Test fun phoneWithSpace() = assertEquals("[number]", m("0300 1234567"))

    @Test fun phoneInSentenceKeepsSurroundingSpaces() =
        assertEquals("Call [number] now", m("Call 03001234567 now"))

    @Test fun cardGroups() = assertEquals("Card: [number]", m("Card: 4111 1111 1111 1111"))

    @Test fun exactlySixDigits() = assertEquals("PIN [number]", m("PIN 123456"))

    @Test fun shortAmountStays() = assertEquals("Rs 500", m("Rs 500"))

    @Test fun timeStays() = assertEquals("12:45", m("12:45"))

    @Test fun fiveDigitsStay() = assertEquals("12345", m("12345"))

    @Test fun commaGroupedAmountStays() = assertEquals("Rs 1,000,000", m("Rs 1,000,000"))

    @Test fun twoNumbers() = assertEquals("[number] aur [number]", m("0300-1234567 aur 0321 7654321"))

    @Test fun nullStaysNull() = assertNull(Masking.mask(null))
}
