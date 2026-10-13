package pk.rehnuma.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.rehnuma.app.screen.TestScreens.el
import pk.rehnuma.app.screen.TestScreens.snap

class SafetyGuardTest {

    private fun sensitive(s: ScreenSnapshot, reason: String) =
        assertEquals(GuardResult.Sensitive(reason), SafetyGuard.check(s))

    private fun safe(s: ScreenSnapshot) = assertEquals(GuardResult.Safe, SafetyGuard.check(s))

    private fun input(id: Int, top: Int, text: String? = null, hint: String? = null, viewId: String? = null) =
        el(id, text = text, hint = hint, viewId = viewId, cls = "EditText", clickable = true, editable = true,
            bounds = listOf(40, top, 1040, top + 120))

    private fun label(id: Int, top: Int, text: String) =
        el(id, text = text, bounds = listOf(40, top, 1040, top + 60))

    // (a)
    @Test fun passwordFieldFlag() = sensitive(snap(input(1, 600), hasPasswordField = true), "password_field")

    // (b)
    @Test fun inputHintNamesPin() = sensitive(snap(input(1, 600, hint = "Enter PIN")), "sensitive_input")

    @Test fun inputViewIdSnakeCase() = sensitive(snap(input(1, 600, viewId = "et_mpin")), "sensitive_input")

    @Test fun inputViewIdCamelCase() = sensitive(snap(input(1, 600, viewId = "etPassword")), "sensitive_input")

    @Test fun inputShortTextOtp() = sensitive(snap(input(1, 600, text = "OTP")), "sensitive_input")

    @Test fun inputUrduHint() = sensitive(snap(input(1, 600, hint = "پاس ورڈ لکھیں")), "sensitive_input")

    @Test fun longChatMessageInInputIsSafe() = safe(
        snap(input(1, 2000, text = "Beta mera WhatsApp password bhool gaya hai, kal yaad dilana please")),
    )

    // (c)
    @Test fun labelAboveInput() = sensitive(snap(label(1, 500, "Enter your PIN"), input(2, 600)), "sensitive_label")

    @Test fun cardNumberLabel() = sensitive(snap(label(1, 500, "Card Number"), input(2, 580)), "sensitive_label")

    @Test fun urduLabelAboveInput() = sensitive(snap(label(1, 500, "پن کوڈ"), input(2, 600)), "sensitive_label")

    @Test fun urduKhufiyaCode() = sensitive(snap(label(1, 500, "اپنا خفیہ کوڈ"), input(2, 600)), "sensitive_label")

    @Test fun apnaDoesNotTrigger() = safe(snap(label(1, 500, "اپنا نمبر لکھیں"), input(2, 600)))

    @Test fun pinnedDoesNotTrigger() = safe(snap(label(1, 500, "Pinned messages"), input(2, 600)))

    @Test fun labelTooFarAboveInputIsSafe() = safe(snap(label(1, 500, "PIN"), input(2, 1000)))

    @Test fun inputAboveLabelIsSafe() = safe(snap(input(1, 300), label(2, 500, "PIN")))

    @Test fun labelWithoutAnyInputIsSafe() = safe(snap(label(1, 500, "Change PIN")))

    @Test fun longChatMessageAboveComposeBoxIsSafe() = safe(
        snap(
            label(1, 1900, "Ammi ne kaha tha password kisi ko mat batana, theek hai na beta"),
            input(2, 2050, hint = "Message"),
        ),
    )

    // (d)
    @Test fun customPinKeypad() {
        val keys = (0..9).map { d ->
            el(10 + d, text = d.toString(), cls = "Button", clickable = true,
                bounds = listOf(100 * d, 1800, 100 * d + 90, 1890))
        }
        sensitive(snap(*keys.toTypedArray()), "pin_keypad")
    }

    @Test fun keypadKeysLabelledByDescription() {
        val keys = (0..9).map { d ->
            el(10 + d, desc = d.toString(), cls = "ImageView", clickable = true)
        }
        sensitive(snap(*keys.toTypedArray()), "pin_keypad")
    }

    @Test fun calendarDaysWithoutZeroAreSafe() {
        val days = (1..9).flatMap { d -> listOf(d, d) }.mapIndexed { i, d ->
            el(i + 1, text = d.toString(), clickable = true)
        }
        safe(snap(*days.toTypedArray()))
    }

    @Test fun nonClickableDigitsAreSafe() {
        val digits = (0..9).map { d -> el(d + 1, text = d.toString()) }
        safe(snap(*digits.toTypedArray()))
    }

    @Test fun ordinaryWhatsAppChatListIsSafe() = safe(
        snap(
            el(1, text = "Chats", clickable = true),
            el(2, text = "Sara", label = "Sara · Kal milte hain", clickable = true),
            el(3, desc = "New chat", cls = "ImageButton", clickable = true),
            el(4, hint = "Search…", cls = "EditText", editable = true, clickable = true),
        ),
    )

    @Test fun regexIsCaseInsensitive() = assertTrue(SafetyGuard.SENSITIVE_RE.containsMatchIn("ENTER M-PIN"))
}
