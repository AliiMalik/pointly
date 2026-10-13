package pk.rehnuma.app.screen

/**
 * Replaces any run of 6+ digits (single spaces or dashes allowed between digits) with `[number]`
 * before anything leaves the phone (SPEC rule 6, 5.3). The server applies the same rule again.
 *
 * The pattern is SPEC 5.3's with the optional separator moved between digits, so a trailing space
 * or dash after the number is kept ("0300 1234567 se" -> "[number] se").
 */
object Masking {
    const val PLACEHOLDER = "[number]"
    val LONG_NUMBER = Regex("""(?<!\d)\d(?:[\s-]?\d){5,}(?!\d)""")

    fun mask(s: String?): String? = s?.let { LONG_NUMBER.replace(it, PLACEHOLDER) }
}
