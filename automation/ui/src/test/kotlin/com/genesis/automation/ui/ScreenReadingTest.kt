package com.genesis.automation.ui

import com.genesis.core.model.RiskLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenReadingTest {

    private suspend fun chat(rtl: Boolean = false, idsChanged: Boolean = false) =
        FakeChatApp(foreground = "com.example.chat", rtl = rtl, idsChanged = idsChanged).apply { outgoing += "تمام" }.snapshot()!!

    @Test
    fun `locator prefers resource id`() = runTest {
        val r = Locator.find(chat(), Selector(resourceIds = listOf("com.example.chat:id/send"), descriptions = listOf("Send")))
        assertNotNull(r)
        assertEquals(MatchStrategy.RESOURCE_ID, r.strategy)
        assertTrue(!r.indicatesUiChange(Selector(resourceIds = listOf("com.example.chat:id/send"))))
    }

    @Test
    fun `locator falls back to description and reports ui change`() = runTest {
        val sel = Selector(resourceIds = listOf("com.example.chat:id/send"), descriptions = listOf("ارسال"))
        val r = Locator.find(chat(idsChanged = true), sel)
        assertNotNull(r, "Arabic hamza variants should match")
        assertEquals(MatchStrategy.DESCRIPTION, r.strategy)
        assertTrue(r.indicatesUiChange(sel))
    }

    @Test
    fun `password text is never matched`() {
        val s = ScreenSnapshot("p", listOf(UiElement(1, Role.TEXT_FIELD, text = "secret", bounds = Bounds(0, 0, 100, 100), editable = true, password = true)), 1080, 2000, 0)
        assertNull(Locator.find(s, Selector(texts = listOf("secret"))))
    }

    @Test
    fun `chat reader splits incoming and outgoing in LTR`() = runTest {
        val c = ChatScreenReader(ChatProfile.GENERIC).read(chat())
        assertTrue(!c.rtl)
        assertEquals(listOf("السلام عليكم", "متى نلتقي؟"), c.messages.filter { it.direction == Direction.INCOMING }.map { it.text })
        assertEquals(listOf("تمام"), c.messages.filter { it.direction == Direction.OUTGOING }.map { it.text })
        assertEquals("متى نلتقي؟", c.lastIncoming()?.text)
    }

    @Test
    fun `chat reader handles RTL layouts and ignores timestamps`() = runTest {
        val c = ChatScreenReader(ChatProfile.GENERIC).read(chat(rtl = true))
        assertTrue(c.rtl)
        assertEquals("متى نلتقي؟", c.lastIncoming()?.text)
        assertTrue(c.messages.none { it.text.startsWith("10:") })
        assertEquals(listOf("تمام"), c.messages.filter { it.direction == Direction.OUTGOING }.map { it.text })
    }

    @Test
    fun `fingerprint separates content changes from layout changes`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val a = ScreenFingerprint.of(app.snapshot()!!)
        app.incoming += "رسالة جديدة"
        val b = ScreenFingerprint.of(app.snapshot()!!)
        assertEquals(ScreenChange.CONTENT, a.compare(b))
        app.idsChanged = true
        val c = ScreenFingerprint.of(app.snapshot()!!)
        assertEquals(ScreenChange.STRUCTURE, b.compare(c))
        app.foreground = "other"
        assertEquals(ScreenChange.APP, c.compare(ScreenFingerprint.of(app.snapshot()!!)))
    }

    @Test
    fun `safety denies critical buttons, password fields and protected screens`() {
        val policy = SafetyPolicy(setOf("a"))
        val screen = ScreenSnapshot("a", emptyList(), 100, 100, 0)
        val pay = UiElement(1, Role.BUTTON, text = "ادفع الآن", bounds = Bounds(0, 0, 10, 10))
        assertIs<GuardVerdict.Deny>(policy.check(UiOp.Tap(pay), screen))
        val pw = UiElement(2, Role.TEXT_FIELD, bounds = Bounds(0, 0, 10, 10), editable = true, password = true)
        assertIs<GuardVerdict.Deny>(policy.check(UiOp.SetText(pw, "x"), screen))
        val ok = UiElement(3, Role.BUTTON, description = "إرسال", bounds = Bounds(0, 0, 10, 10))
        assertIs<GuardVerdict.Deny>(policy.check(UiOp.Tap(ok), screen.copy(secure = true)))
        assertIs<GuardVerdict.Allow>(policy.check(UiOp.Tap(ok), screen))
        assertIs<GuardVerdict.Deny>(policy.check(UiOp.LaunchApp("com.android.vending"), null))
        assertIs<GuardVerdict.Deny>(SafetyPolicy(setOf("com.paypal.android.p2pmobile")).check(UiOp.LaunchApp("com.paypal.android.p2pmobile"), null))
    }

    @Test
    fun `report json never contains the message text`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val clock = FakeClock()
        val secret = "رسالة خاصة جداً"
        val runner = UiRunner(app, clock, MutableStopSignal(), { ApprovalDecision.Approved }, EffectLedger(InMemoryEffectStore(), clock), Pacer(clock))
        val json = runner.run(ChatRecipes.sendInOpenChat("t", ChatProfile.GENERIC, app.pkg, "x", secret, "k")).toJson()
        assertTrue(!json.contains(secret))
        assertTrue(json.contains("\"result\": \"SUCCESS\""))
        assertEquals(RiskLevel.HIGH, Step.Effect("k", Selector(texts = listOf("a")), "a", "t", "c",
            OutgoingTextProof(ChatScreenReader(ChatProfile.GENERIC), "c"), Verification.TextShown("c"), label = "l").risk)
    }
}
