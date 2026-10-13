package pk.rehnuma.app.screen

sealed class GuardResult {
    data object Safe : GuardResult()
    data class Sensitive(val reason: String) : GuardResult()
}

/**
 * Decides whether a screen asks for a PIN, password, OTP or card details (SPEC 5.4).
 * A SENSITIVE screen is never uploaded, saved or pointed at (rule 3).
 */
object SafetyGuard {
    val SENSITIVE_RE = Regex(
        """\b(pin|mpin|m-pin|password|passcode|otp|cvv|cvc|card number|security code)\b""" +
            """|(?<!\p{L})(پن|پن کوڈ|پاس ورڈ|خفیہ کوڈ)(?!\p{L})""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Rule (c): only short labels count, so a long chat message mentioning "password" does not. */
    const val SHORT_TEXT = 30

    /** Rule (c): an input that starts within this many pixels below the label. */
    const val LABEL_TO_INPUT_PX = 300

    private val SINGLE_DIGIT = Regex("""^\d$""")
    private val CAMEL = Regex("""(?<=[a-z])(?=[A-Z])""")
    private val ID_SEPARATORS = Regex("""[_.\-/]+""")

    fun check(snapshot: ScreenSnapshot): GuardResult {
        // (a) a real password field
        if (snapshot.hasPasswordField) return GuardResult.Sensitive("password_field")

        val editables = snapshot.elements.filter { it.editable }

        // (b) an input whose id, hint or short text/description names a secret
        for (e in editables) {
            if (matches(viewIdWords(e.viewId)) || matches(e.hint) || matchesShort(e.text) || matchesShort(e.desc)) {
                return GuardResult.Sensitive("sensitive_input")
            }
        }

        // (c) a short label naming a secret, with an input starting just below it
        for (e in snapshot.elements) {
            if (e.editable) continue
            val text = e.text ?: e.desc ?: e.label ?: continue
            if (!matchesShort(text)) continue
            val below = editables.any { it.top >= e.top && it.top <= e.bottom + LABEL_TO_INPUT_PX }
            if (below) return GuardResult.Sensitive("sensitive_label")
        }

        // (d) a custom PIN keypad: clickable keys labelled with every digit 0-9
        val digits = snapshot.elements
            .filter { it.clickable }
            .mapNotNull { it.displayLabel?.trim() }
            .filter { SINGLE_DIGIT.matches(it) }
            .toSet()
        if (digits.size >= 10) return GuardResult.Sensitive("pin_keypad")

        return GuardResult.Safe
    }

    private fun matches(s: String?) = s != null && SENSITIVE_RE.containsMatchIn(s)

    private fun matchesShort(s: String?) = s != null && s.length <= SHORT_TEXT && SENSITIVE_RE.containsMatchIn(s)

    /** "et_pin" -> "et pin", "etPassword" -> "et Password", so `\b` sees the words. */
    internal fun viewIdWords(viewId: String?): String? =
        viewId?.replace(CAMEL, " ")?.replace(ID_SEPARATORS, " ")
}
