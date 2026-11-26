package com.genesis.app

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.genesis.app.engine.PrototypeTasks
import com.genesis.app.engine.SystemEngineClock
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.automation.ui.ApprovalDecision
import com.genesis.automation.ui.ApprovalGate
import com.genesis.automation.ui.ApprovalRequest
import com.genesis.automation.ui.ChatProfile
import com.genesis.automation.ui.ChatRecipes
import com.genesis.automation.ui.ChatScreenReader
import com.genesis.automation.ui.Direction
import com.genesis.automation.ui.EffectLedger
import com.genesis.automation.ui.EffectState
import com.genesis.automation.ui.InMemoryEffectStore
import com.genesis.automation.ui.Pacer
import com.genesis.automation.ui.RunReport
import com.genesis.automation.ui.RunResult
import com.genesis.automation.ui.UiRunner
import com.genesis.automation.ui.UiTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real engine through the real accessibility service on an emulator, against a
 * separate fixture chat app and the system Settings app. Approval is answered by the test
 * (the on-screen approval panel is checked by hand on a real phone).
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {

    companion object {
        private const val FIXTURE = "com.genesis.fixture.chat"
        private const val TAG = "GenesisE2E"

        @BeforeClass @JvmStatic
        fun enableService() {
            val ua = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val component = "com.genesis.app/com.genesis.app.service.GenesisAccessibilityService"
            shell(ua, "settings put secure enabled_accessibility_services $component")
            shell(ua, "settings put secure accessibility_enabled 1")
            shell(ua, "input keyevent KEYCODE_WAKEUP")
            shell(ua, "wm dismiss-keyguard")
            runBlocking {
                repeat(60) {
                    if (GenesisAccessibilityService.instance != null) return@runBlocking
                    delay(500)
                }
            }
            check(GenesisAccessibilityService.instance != null) { "accessibility service did not start" }
        }

        private fun shell(ua: UiAutomation, cmd: String) {
            ua.executeShellCommand(cmd).use { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() }
        }
    }

    private val service get() = GenesisAccessibilityService.instance!!
    private val approvals = mutableListOf<ApprovalRequest>()

    private fun runner(store: InMemoryEffectStore, approve: Boolean = true) = UiRunner(
        service, SystemEngineClock, service.stopSignal,
        ApprovalGate { r -> approvals += r; if (approve) ApprovalDecision.Approved else ApprovalDecision.Rejected },
        EffectLedger(store, SystemEngineClock), Pacer(SystemEngineClock),
    )

    private fun openFixture(variant: Boolean = false, ignoreTaps: Int = 0, invisible: Boolean = false) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(
            Intent().setClassName(FIXTURE, "$FIXTURE.ChatActivity")
                .putExtra("variant", variant).putExtra("ignoreTaps", ignoreTaps).putExtra("invisible", invisible)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        runBlocking {
            repeat(40) {
                if (service.snapshot()?.packageName == FIXTURE) { delay(500); return@runBlocking }
                delay(250)
            }
        }
    }

    private fun sentCount(): Int = runBlocking {
        val s = service.snapshot() ?: return@runBlocking -1
        s.elements.firstOrNull { it.resourceId == "$FIXTURE:id/sent_count" }?.text?.substringAfter("=")?.toIntOrNull() ?: -1
    }

    private fun sendTask(key: String, text: String): UiTask = ChatRecipes.sendInOpenChat("e2e", ChatProfile.FIXTURE, FIXTURE, "Ahmad", text, key)

    private fun run(r: UiRunner, t: UiTask): RunReport = runBlocking {
        service.beginTask()
        try { r.run(t) } finally { service.endTask() }
    }.also { Log.i(TAG, it.toJson().replace("\n", " ")) }

    @Test
    fun t1_readsChatAndDirections() {
        openFixture()
        val snap = runBlocking { service.snapshot() }!!
        val chat = ChatScreenReader(ChatProfile.FIXTURE).read(snap)
        assertTrue(chat.inputFound && chat.sendFound)
        assertEquals(listOf("السلام عليكم", "هل أنت متاح غداً؟"), chat.messages.filter { it.direction == Direction.INCOMING }.map { it.text })
    }

    @Test
    fun t3_sendsArabicOnceAndVerifies() {
        openFixture()
        val store = InMemoryEffectStore()
        val text = "نعم، متاح غداً بعد العصر"
        val report = run(runner(store), sendTask("t3", text))
        assertEquals(report.summary, RunResult.SUCCESS, report.result)
        assertEquals(1, sentCount())
        assertEquals(1, approvals.size)
        assertEquals(EffectState.VERIFIED, store.get("t3")?.state)
        // Running the same task again must not send again.
        val again = run(runner(store), sendTask("t3", text))
        assertEquals(RunResult.SUCCESS, again.result)
        assertEquals(1, sentCount())
    }

    @Test
    fun t4_changedIdsAreDetectedAndHandled() {
        openFixture(variant = true)
        val report = run(runner(InMemoryEffectStore()), sendTask("t4", "اختبار واجهة متغيرة"))
        assertEquals(report.summary, RunResult.SUCCESS, report.result)
        assertEquals(1, sentCount())
        assertTrue("UI change must be logged", report.steps.any { it.uiChangeDetected })
    }

    @Test
    fun t5_unresponsiveSendRetriedWithoutDuplicate() {
        openFixture(ignoreTaps = 1)
        val store = InMemoryEffectStore()
        val report = run(runner(store), sendTask("t5", "اختبار زر لا يستجيب"))
        assertEquals(report.summary, RunResult.SUCCESS, report.result)
        assertEquals(1, sentCount())
        assertEquals(2, store.get("t5")?.attempts)
    }

    @Test
    fun t5b_unconfirmableSendIsNotRepeated() {
        openFixture(invisible = true)
        val store = InMemoryEffectStore()
        val report = run(runner(store), sendTask("t5b", "اختبار نتيجة غير مرئية"))
        assertEquals(RunResult.NEEDS_USER, report.result)
        assertEquals(1, sentCount())
        assertEquals(EffectState.UNKNOWN, store.get("t5b")?.state)
    }

    @Test
    fun t6_rejectionSendsNothing() {
        openFixture()
        val report = run(runner(InMemoryEffectStore(), approve = false), sendTask("t6", "يجب ألا تُرسل"))
        assertEquals(RunResult.REJECTED_BY_USER, report.result)
        assertEquals(0, sentCount())
    }

    @Test
    fun t2_settingsAboutPhone() {
        val report = run(runner(InMemoryEffectStore()), PrototypeTasks.settingsAbout())
        assertEquals(report.summary, RunResult.SUCCESS, report.result)
    }
}
