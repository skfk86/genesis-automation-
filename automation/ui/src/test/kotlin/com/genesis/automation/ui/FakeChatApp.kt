package com.genesis.automation.ui

/** Virtual time: sleeping just advances the clock, so tests run instantly. */
class FakeClock(var now: Long = 1_000_000L) : EngineClock {
    override fun nowMillis() = now
    override suspend fun sleep(millis: Long) { now += millis }
}

/**
 * A simulated messaging app behind a fake backend. Behaviours model the failure cases the
 * owner asked about: changed ids, a send button that ignores the first tap, and a send whose
 * result never shows on screen.
 */
class FakeChatApp(
    val pkg: String = "com.example.chat",
    var idsChanged: Boolean = false,
    var ignoreFirstSendTaps: Int = 0,
    /** The message is sent but the screen never shows it (to test the "unknown" path). */
    var sendInvisible: Boolean = false,
    /** Clears the draft but shows no bubble: outcome ambiguous. */
    var clearDraftWithoutBubble: Boolean = false,
    val incoming: MutableList<String> = mutableListOf("السلام عليكم", "متى نلتقي؟"),
    var rtl: Boolean = false,
    var foreground: String? = null,
) : UiBackend {
    override val id = "fake"
    val outgoing = mutableListOf<String>()
    var actuallySent = 0
    var draft = ""
    var available = true
    val performed = mutableListOf<UiOp>()
    private var sendTaps = 0

    private val width = 1080
    private val height = 2340

    private fun id(name: String) = if (idsChanged) "$pkg:id/${name}_v2" else "$pkg:id/$name"

    override suspend fun snapshot(): ScreenSnapshot? {
        if (!available) return null
        val fg = foreground ?: return ScreenSnapshot("com.android.launcher", emptyList(), width, height, 0)
        if (fg != pkg) return ScreenSnapshot(fg, emptyList(), width, height, 0)
        val els = mutableListOf<UiElement>()
        var ref = 0
        els += UiElement(ref++, Role.TEXT, text = "أحمد", resourceId = id("title"), bounds = Bounds(200, 80, 600, 180))
        var top = 400
        // Messages in order: incoming first, then outgoing (enough for tests).
        for (m in incoming) {
            val b = if (rtl) Bounds(width - 700, top, width - 40, top + 100) else Bounds(40, top, 700, top + 100)
            els += UiElement(ref++, Role.TEXT, text = m, resourceId = id("message_text"), bounds = b)
            els += UiElement(ref++, Role.TEXT, text = "10:4${ref % 10}", bounds = Bounds(b.right - 120, b.bottom, b.right, b.bottom + 30))
            top += 160
        }
        for (m in outgoing) {
            val b = if (rtl) Bounds(40, top, 700, top + 100) else Bounds(width - 700, top, width - 40, top + 100)
            els += UiElement(ref++, Role.TEXT, text = m, resourceId = id("message_text"), bounds = b)
            top += 160
        }
        val inputBounds = if (rtl) Bounds(200, 2150, width - 40, 2280) else Bounds(40, 2150, width - 200, 2280)
        val sendBounds = if (rtl) Bounds(40, 2150, 180, 2280) else Bounds(width - 180, 2150, width - 40, 2280)
        els += UiElement(ref++, Role.TEXT_FIELD, text = draft.ifEmpty { null }, resourceId = id("input"), bounds = inputBounds, editable = true, clickable = true)
        els += UiElement(ref, Role.BUTTON, description = "إرسال", resourceId = id("send"), bounds = sendBounds, clickable = true)
        return ScreenSnapshot(pkg, els, width, height, 0)
    }

    override suspend fun perform(op: UiOp): OpResult {
        performed += op
        when (op) {
            is UiOp.LaunchApp -> foreground = op.packageName
            is UiOp.SetText -> if (op.target.editable) draft = op.text
            is UiOp.Tap -> if (op.target.resourceId == id("send")) {
                sendTaps++
                if (sendTaps <= ignoreFirstSendTaps) return OpResult(true, "tap delivered")
                if (draft.isNotEmpty()) {
                    actuallySent++
                    if (clearDraftWithoutBubble) { draft = ""; return OpResult(true) }
                    if (!sendInvisible) outgoing += draft
                    draft = ""
                }
            }
            is UiOp.Scroll, UiOp.Back -> Unit
        }
        return OpResult(true)
    }
}
