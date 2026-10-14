package pk.pointly.app.screen

object TestScreens {
    fun el(
        id: Int,
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        label: String? = null,
        viewId: String? = null,
        cls: String = "TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        bounds: List<Int> = listOf(0, id * 100, 1080, id * 100 + 80),
    ) = ScreenElement(
        id = id, text = text, desc = desc, hint = hint, label = label, viewId = viewId, cls = cls,
        clickable = clickable, editable = editable, scrollable = scrollable, bounds = bounds,
    )

    fun snap(
        vararg elements: ScreenElement,
        pkg: String = "com.example",
        hasPasswordField: Boolean = false,
        windowId: Int = 1,
    ) = ScreenSnapshot(
        packageName = pkg,
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
        signature = ElementOrdering.signature(pkg, elements.toList()),
        capturedAtMs = 0,
        readMs = 0,
        hasPasswordField = hasPasswordField,
        windowId = windowId,
    )
}
