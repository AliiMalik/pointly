package pk.pointly.app.screen

object Masking {
    const val PLACEHOLDER = "[number]"
    val LONG_NUMBER = Regex("""(?<!\d)\d(?:[\s-]?\d){5,}(?!\d)""")

    fun mask(s: String?): String? = s?.let { LONG_NUMBER.replace(it, PLACEHOLDER) }
}
