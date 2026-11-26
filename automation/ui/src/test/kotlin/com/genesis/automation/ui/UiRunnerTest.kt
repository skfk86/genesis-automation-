package com.genesis.automation.ui

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiRunnerTest {

    private val msg = "وعليكم السلام، نلتقي غداً الساعة الخامسة إن شاء الله"

    private class Harness(val app: FakeChatApp, approve: Boolean = true) {
        val clock = FakeClock()
        val stop = MutableStopSignal()
        val store = InMemoryEffectStore()
        val approvals = mutableListOf<ApprovalRequest>()
        val gate = ApprovalGate { r -> approvals += r; if (approve) ApprovalDecision.Approved else ApprovalDecision.Rejected }
        val runner = UiRunner(app, clock, stop, gate, EffectLedger(store, clock), Pacer(clock, random = { 0.0 }))
    }

    private fun task(app: FakeChatApp, key: String = "send-1", profile: ChatProfile = ChatProfile.GENERIC) =
        ChatRecipes.sendInOpenChat("t", profile, app.pkg, "أحمد", msg, key)

    @Test
    fun `sends once with approval and verifies the bubble`() = runTest {
        val h = Harness(FakeChatApp(foreground = "com.example.chat"))
        val report = h.runner.run(task(h.app))
        assertEquals(RunResult.SUCCESS, report.result, report.summary)
        assertEquals(1, h.app.actuallySent)
        assertEquals(1, h.approvals.size)
        assertEquals(msg, h.approvals.single().content)
        assertEquals(EffectState.VERIFIED, report.effects.single().state)
    }

    @Test
    fun `works the same in a right-to-left layout`() = runTest {
        val h = Harness(FakeChatApp(foreground = "com.example.chat", rtl = true))
        val report = h.runner.run(task(h.app))
        assertEquals(RunResult.SUCCESS, report.result, report.summary)
        assertEquals(1, h.app.actuallySent)
    }

    @Test
    fun `changed ids are detected and handled by fallbacks`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat", idsChanged = true)
        val profile = ChatProfile.GENERIC.copy(
            input = ChatProfile.GENERIC.input.copy(resourceIds = listOf("com.example.chat:id/input")),
            send = ChatProfile.GENERIC.send.copy(resourceIds = listOf("com.example.chat:id/send")),
        )
        val h = Harness(app)
        val report = h.runner.run(task(app, profile = profile))
        assertEquals(RunResult.SUCCESS, report.result, report.summary)
        assertEquals(1, app.actuallySent)
        assertTrue(report.steps.any { it.uiChangeDetected }, "UI change should be logged")
    }

    @Test
    fun `unresponsive send is retried only because the draft proves nothing was sent`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat", ignoreFirstSendTaps = 1)
        val h = Harness(app)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.SUCCESS, report.result, report.summary)
        assertEquals(1, app.actuallySent, "exactly one message must go out")
        assertEquals(2, report.effects.single().attempts)
        assertEquals(1, h.approvals.size, "the retry does not ask again for the same approved text")
    }

    @Test
    fun `send without visible result is never repeated`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat", sendInvisible = true)
        val h = Harness(app)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.NEEDS_USER, report.result)
        assertEquals(1, app.actuallySent)
        assertEquals(1, app.performed.count { it is UiOp.Tap })
        assertEquals(EffectState.UNKNOWN, report.effects.single().state)
    }

    @Test
    fun `cleared draft without bubble is ambiguous and stops`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat", clearDraftWithoutBubble = true)
        val h = Harness(app)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.NEEDS_USER, report.result)
        assertEquals(1, app.performed.count { it is UiOp.Tap })
    }

    @Test
    fun `rejection sends nothing`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val h = Harness(app, approve = false)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.REJECTED_BY_USER, report.result)
        assertEquals(0, app.actuallySent)
        assertTrue(app.performed.none { it is UiOp.Tap })
    }

    @Test
    fun `running the same task again after success does not send again`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val h = Harness(app)
        h.runner.run(task(app))
        app.draft = ""
        val second = h.runner.run(task(app))
        assertEquals(1, app.actuallySent)
        assertTrue(second.steps.any { it.outcome == StepOutcome.SKIPPED_ALREADY_DONE })
    }

    @Test
    fun `after an unknown outcome a rerun is blocked until a person checks`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat", sendInvisible = true)
        val h = Harness(app)
        h.runner.run(task(app))
        app.sendInvisible = false
        val second = h.runner.run(task(app))
        assertEquals(RunResult.NEEDS_USER, second.result)
        assertEquals(1, app.actuallySent)
    }

    @Test
    fun `a crash after the attempt is recorded blocks a second send`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val h = Harness(app)
        // Simulate a previous run that died right after recording the attempt.
        EffectLedger(h.store, h.clock).mark("send-1", "x", EffectState.ATTEMPTED, countAttempt = true)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.NEEDS_USER, report.result)
        assertEquals(0, app.actuallySent)
    }

    @Test
    fun `stop signal ends the task before sending`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val h = Harness(app)
        val gate = ApprovalGate { h.stop.stop("user touched the screen"); ApprovalDecision.Approved }
        val runner = UiRunner(app, h.clock, h.stop, gate, EffectLedger(h.store, h.clock), Pacer(h.clock))
        val report = runner.run(task(app))
        assertEquals(RunResult.STOPPED, report.result)
        assertEquals(0, app.actuallySent)
    }

    @Test
    fun `apps outside the task are blocked`() = runTest {
        val app = FakeChatApp(foreground = "com.other.app")
        val h = Harness(app)
        val t = task(app).copy(steps = listOf(Step.Tap(Selector(texts = listOf("x")), "tap")))
        val report = h.runner.run(t)
        assertTrue(report.result == RunResult.FAILED || report.result == RunResult.STOPPED)
        assertTrue(app.performed.isEmpty())
    }

    @Test
    fun `send limit per period stops further sends`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat")
        val h = Harness(app)
        repeat(5) { i ->
            app.draft = ""
            assertEquals(RunResult.SUCCESS, h.runner.run(task(app, key = "k$i")).result)
        }
        val sixth = h.runner.run(task(app, key = "k6"))
        assertEquals(RunResult.NEEDS_USER, sixth.result)
        assertEquals(5, app.actuallySent)
    }

    @Test
    fun `screen not readable fails cleanly`() = runTest {
        val app = FakeChatApp(foreground = "com.example.chat").apply { available = false }
        val h = Harness(app)
        val report = h.runner.run(task(app))
        assertEquals(RunResult.FAILED, report.result)
        assertEquals(0, app.actuallySent)
    }
}
