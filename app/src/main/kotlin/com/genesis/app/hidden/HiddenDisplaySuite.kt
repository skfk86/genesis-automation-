package com.genesis.app.hidden

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.genesis.app.engine.DeviceInfo
import com.genesis.app.engine.Engine
import com.genesis.app.engine.PrototypeTasks
import com.genesis.app.engine.SystemEngineClock
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.app.service.NodeMapper
import com.genesis.automation.ui.ApprovalDecision
import com.genesis.automation.ui.ApprovalGate
import com.genesis.automation.ui.ChatProfile
import com.genesis.automation.ui.ChatRecipes
import com.genesis.automation.ui.ChatScreenReader
import com.genesis.automation.ui.Direction
import com.genesis.automation.ui.EffectLedger
import com.genesis.automation.ui.InMemoryEffectStore
import com.genesis.automation.ui.Locator
import com.genesis.automation.ui.Pacer
import com.genesis.automation.ui.Role
import com.genesis.automation.ui.RunReport
import com.genesis.automation.ui.RunResult
import com.genesis.automation.ui.ScreenChange
import com.genesis.automation.ui.ScreenFingerprint
import com.genesis.automation.ui.ScreenSnapshot
import com.genesis.automation.ui.ScrollDirection
import com.genesis.automation.ui.Selector
import com.genesis.automation.ui.Step
import com.genesis.automation.ui.UiOp
import com.genesis.automation.ui.UiRunner
import com.genesis.automation.ui.UiTask
import com.genesis.automation.ui.Verification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Result of one requirement. Status is PASS, FAIL, SKIPPED or PHONE_CHECK (needs the person). */
@Serializable
data class HiddenCheck(
    val id: String,
    val title: String,
    val status: String,
    val detail: String,
    val metrics: Map<String, String> = emptyMap(),
)

@Serializable
data class HiddenSuiteReport(
    val kind: String = "hidden-display-feasibility",
    val device: DeviceInfo,
    val hostMode: String,
    val shizukuVersion: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val checks: List<HiddenCheck>,
    val runs: List<RunReport>,
    val actionPaths: Map<String, Int>,
    val resources: Map<String, String>,
    val events: List<String>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true }
        private const val LATEST = "hidden_report_latest.json"

        fun save(context: Context, r: HiddenSuiteReport) {
            File(context.filesDir, LATEST).writeText(r.toJson())
            File(context.filesDir, "hidden_reports.jsonl").appendText(r.toJson().replace("\n", "") + "\n")
        }

        fun latestJson(context: Context): String? = File(context.filesDir, LATEST).takeIf { it.exists() }?.readText()
    }
}

/** How the loss test removes and restores the host. On the phone this is Shizuku; in emulator tests it is simulated. */
interface HostLossDriver {
    val description: String
    suspend fun loseHost()
    suspend fun restoreHost(): IHiddenDisplayHost?
}

class ShizukuLossDriver : HostLossDriver {
    override val description = "the Shizuku user service process was removed (same as Shizuku being lost), then bound again"
    override suspend fun loseHost() = HiddenDisplayManager.disconnect(kill = true, reconnect = false)
    override suspend fun restoreHost(): IHiddenDisplayHost? {
        HiddenDisplayManager.connect()
        repeat(60) {
            HiddenDisplayManager.host?.let { return it }
            delay(250)
        }
        return null
    }
}

/**
 * Proves, on a real device, each requirement of the hidden display, and measures it. Nothing
 * here sends a real message: sends happen only in the separate test chat app, and WhatsApp is only
 * opened, read, scrolled and typed into its search field, never sent from.
 */
class HiddenDisplaySuite(
    private val context: Context,
    private val service: GenesisAccessibilityService,
    private val lossDriver: HostLossDriver,
    private val progress: (String) -> Unit = {},
) {
    private val checks = mutableListOf<HiddenCheck>()
    private val runs = mutableListOf<RunReport>()
    private val paths = mutableMapOf<String, Int>()
    private val resources = linkedMapOf<String, String>()
    private val monitor = MainMonitor(service)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    suspend fun run(): HiddenSuiteReport = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val battery0 = battery()
        val cpu0 = Process.getElapsedCpuTime()
        val baseline = service.mainDisplayState()
        resources["mainTopAtStart"] = baseline.topPackage ?: "none"
        service.beginHiddenTask()
        val monitorJob = monitor.start(this)
        try {
            body()
        } catch (t: Throwable) {
            checks += HiddenCheck("ERR", "unexpected error", "FAIL", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            monitorJob.cancel()
            service.endTask()
        }
        resources["batteryStartPct"] = battery0.toString()
        resources["batteryEndPct"] = battery().toString()
        resources["appCpuMs"] = (Process.getElapsedCpuTime() - cpu0).toString()
        resources["appPssKb"] = Debug.getPss().toString()
        resources["durationMs"] = (System.currentTimeMillis() - started).toString()
        HiddenSuiteReport(
            device = Engine.deviceInfo(context),
            hostMode = HiddenDisplayManager.mode,
            shizukuVersion = HiddenDisplayManager.shizukuVersion(),
            startedAtMillis = started,
            finishedAtMillis = System.currentTimeMillis(),
            checks = checks.toList(),
            runs = runs.toList(),
            actionPaths = paths.toMap(),
            resources = resources.toMap(),
            events = HiddenDisplayManager.events.toList(),
        ).also { HiddenSuiteReport.save(context, it) }
    }

    private suspend fun body(): Unit = coroutineScope {
        val host = HiddenDisplayManager.host
        if (host == null) {
            checks += HiddenCheck("H-1", "create a hidden display", "FAIL", "no privileged host (Shizuku not connected)")
            return@coroutineScope
        }

        // H-1: create the display.
        progress("H-1: إنشاء الشاشة المخفية")
        val s1 = HiddenSession.create(context, host).getOrElse {
            checks += HiddenCheck("H-1", "create a hidden display", "FAIL", it.message ?: "failed")
            return@coroutineScope
        }
        checks += HiddenCheck("H-1", "create a hidden display", "PASS",
            "display ${s1.displayId}, ${s1.width}x${s1.height}, flags=${s1.createInfo.optString("flagSet")}",
            mapOf(
                "flagSet" to s1.createInfo.optString("flagSet"),
                "imePolicy" to s1.createInfo.optString("imePolicy"),
                "attempts" to s1.createInfo.optJSONArray("attempts").toString(),
                "hostUid" to s1.createInfo.optInt("uid").toString(),
                "visibleToApps" to (displayManager.getDisplay(s1.displayId) != null).toString(),
            ))
        hostStats(s1, "afterCreate")

        // H-2 + H-5/H-6 on the test chat app: launch, read, type Arabic, send once inside the test app, verify.
        val fixtureInstalled = Engine.isInstalled(context, FIXTURE)
        if (!fixtureInstalled) {
            checks += HiddenCheck("H-2", "run a test app on the hidden display", "SKIPPED", "test chat app ($FIXTURE) is not installed")
        } else {
            progress("H-2: تشغيل تطبيق المحادثة التجريبي داخل الشاشة المخفية")
            monitor.expectUnchanged = true
            val backend = HiddenDisplayBackend(service, s1)
            val r = runTask(backend, fixtureTask())
            val seenByA11y = s1.displayId in service.visibleDisplayIds()
            val frames = hostStats(s1, "afterFixture")
            checks += HiddenCheck("H-2", "run a test app on the hidden display and read it", if (r.result == RunResult.SUCCESS) "PASS" else "FAIL",
                r.summary,
                mapOf(
                    "accessibilitySeesDisplay" to seenByA11y.toString(),
                    "framesRendered" to frames,
                    "separateReaderNeeded" to (!seenByA11y).toString(),
                ))
        }

        // Settings on the hidden display: scroll, tap and verify in a real system app.
        progress("H-5: تمرير ونقر داخل الإعدادات على الشاشة المخفية")
        val settingsRun = runTask(HiddenDisplayBackend(service, s1), PrototypeTasks.settingsAbout())
        s1.release()

        // H-3 / H-4 / H-5 on WhatsApp, read-only apart from typing into its search field.
        val waInstalled = Engine.isInstalled(context, WHATSAPP)
        var waInteract: HiddenCheck? = null
        if (!waInstalled) {
            checks += HiddenCheck("H-3", "run WhatsApp on the hidden display", "SKIPPED", "WhatsApp is not installed")
            checks += HiddenCheck("H-4", "read WhatsApp's interface on the hidden display", "SKIPPED", "WhatsApp is not installed")
        } else {
            progress("H-3/H-4: تشغيل واتساب وقراءته داخل الشاشة المخفية")
            val s2 = HiddenSession.create(context, host).getOrNull()
            if (s2 == null) {
                checks += HiddenCheck("H-3", "run WhatsApp on the hidden display", "FAIL", "could not create a display")
            } else {
                waInteract = whatsApp(s2)
                s2.release()
            }
        }

        // H-5 summary: tap, type, scroll.
        val fixtureRun = runs.firstOrNull { it.taskName.startsWith("H-2") }
        val parts = buildList {
            fixtureRun?.let { add("test chat (type+tap send+verify): ${it.result}") }
            add("Settings (scroll+tap+verify): ${settingsRun.result}")
            waInteract?.let { add("WhatsApp: ${it.status} ${it.detail}") }
        }
        val h5ok = settingsRun.result == RunResult.SUCCESS && (fixtureRun == null || fixtureRun.result == RunResult.SUCCESS) &&
            (waInteract == null || waInteract.status == "PASS")
        checks += HiddenCheck("H-5", "tap, type and scroll on the hidden display", if (h5ok) "PASS" else "FAIL", parts.joinToString("; "),
            waInteract?.metrics ?: emptyMap())

        // H-6: every verification step that ran.
        val verifySteps = runs.flatMap { it.steps }.filter { it.step in setOf("Check", "WaitFor", "Effect", "TypeText", "ScrollTo") }
        val verifyFailed = verifySteps.count { it.outcome.name != "OK" }
        checks += HiddenCheck("H-6", "verify the result of each action", if (verifySteps.isNotEmpty() && verifyFailed == 0) "PASS" else "FAIL",
            "${verifySteps.size} verified steps, $verifyFailed failed",
            mapOf("effects" to runs.flatMap { it.effects }.joinToString { "${it.state}/${it.attempts}" }))

        // H-8: the person keeps using the phone while a hidden task runs.
        if (fixtureInstalled) concurrentUse(host) else checks += HiddenCheck("H-8", "use the phone normally during a task", "SKIPPED", "needs the test chat app")

        // H-7: the main screen never changed while hidden tasks ran (H-8 changes it on purpose and is excluded).
        val m = monitor.snapshot()
        val h7ok = m.samples > 0 && m.foreignTops.isEmpty() && m.imeSamples == 0
        checks += HiddenCheck("H-7", "main screen unchanged during hidden work", if (h7ok) "PASS" else "FAIL",
            "samples=${m.samples}, main top apps=${m.tops}, unexpected=${m.foreignTops}, keyboard on main=${m.imeSamples}",
            mapOf("baseline" to (resources["mainTopAtStart"] ?: "none")))

        // H-9: immediate stop.
        if (fixtureInstalled) stopTest(host) else checks += HiddenCheck("H-9", "immediate stop", "SKIPPED", "needs the test chat app")

        // H-10: losing the privileged host, then reconnecting.
        if (fixtureInstalled) lossTest(host) else checks += HiddenCheck("H-10", "losing Shizuku and reconnecting", "SKIPPED", "needs the test chat app")
    }

    // ---------------- WhatsApp (never sends) ----------------

    private suspend fun whatsApp(s: HiddenSession): HiddenCheck {
        val backend = HiddenDisplayBackend(service, s)
        monitor.expectUnchanged = true
        val launched = backend.perform(UiOp.LaunchApp(WHATSAPP))
        var snap: ScreenSnapshot? = null
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000) {
            snap = backend.snapshot()?.takeIf { it.packageName == WHATSAPP && it.elements.size > 3 }
            if (snap != null) break
            delay(300)
        }
        val frames = hostStats(s, "afterWhatsApp")
        val blank = previewBlank(s)
        checks += HiddenCheck("H-3", "run WhatsApp on the hidden display", if (snap != null) "PASS" else "FAIL",
            if (snap != null) "WhatsApp is running on display ${s.displayId}, opened in ${System.currentTimeMillis() - t0} ms" else "launch=${launched.detail}; not readable within 15 s",
            mapOf("launch" to launched.detail, "framesRendered" to frames, "previewBlank" to blank.toString()))
        merge(backend)
        if (snap == null) {
            checks += HiddenCheck("H-4", "read WhatsApp's interface on the hidden display", "FAIL", "no readable WhatsApp window on the hidden display")
            return HiddenCheck("WA", "", "FAIL", "WhatsApp not readable")
        }
        val chatOpen = Locator.find(snap, ChatProfile.WHATSAPP.input) != null
        val rows = snap.elements.count { it.resourceId == "com.whatsapp:id/conversations_row_contact_name" }
        val chat = if (chatOpen) ChatScreenReader(ChatProfile.WHATSAPP).read(snap) else null
        checks += HiddenCheck("H-4", "read WhatsApp's interface on the hidden display", "PASS",
            "elements=${snap.elements.size}, withIds=${snap.elements.count { it.resourceId != null }}, screen=${if (chatOpen) "conversation" else "chat list"}, " +
                (if (chat != null) "messages=${chat.messages.size} (in ${chat.messages.count { it.direction == Direction.INCOMING }}, out ${chat.messages.count { it.direction == Direction.OUTGOING }})" else "chat rows=$rows"),
            mapOf("roles" to snap.elements.groupingBy { it.role }.eachCount().toString(), "previewBlank" to blank.toString()))

        // Scroll the visible list forward and back, and check the content moved.
        val metrics = linkedMapOf<String, String>()
        var scrollOk = false
        val scrollable = snap.visibleElements.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        if (scrollable != null) {
            val before = ScreenFingerprint.of(snap)
            backend.perform(UiOp.Scroll(scrollable, ScrollDirection.FORWARD))
            scrollOk = waitChange(backend, before) != ScreenChange.NONE
            backend.perform(UiOp.Scroll(scrollable, ScrollDirection.BACKWARD))
            delay(600)
        }
        metrics["scroll"] = if (scrollable == null) "nothing scrollable" else scrollOk.toString()

        // Tap: open the search field (or switch tab if search is not found), type into it, verify, clear.
        var tapOk = false
        var typeOk: Boolean? = null
        val screen = backend.snapshot() ?: snap
        val search = Locator.find(screen, WA_SEARCH)
        if (search != null) {
            val before = ScreenFingerprint.of(screen)
            backend.perform(UiOp.Tap(search.element))
            tapOk = waitChange(backend, before) != ScreenChange.NONE
            val field = pollFind(backend, Selector(roles = setOf(Role.TEXT_FIELD), editable = true, allowRoleOnly = true), 3_000)
            if (field != null) {
                backend.perform(UiOp.SetText(field.second.element, TEST_TEXT))
                typeOk = pollCheck(backend, Verification.FieldContains(Selector(roles = setOf(Role.TEXT_FIELD), editable = true, allowRoleOnly = true), TEST_TEXT), 3_000)
                backend.snapshot()?.let { cur -> Locator.find(cur, Selector(roles = setOf(Role.TEXT_FIELD), editable = true, allowRoleOnly = true))?.let { backend.perform(UiOp.SetText(it.element, "")) } }
            } else typeOk = false
            backend.key(android.view.KeyEvent.KEYCODE_BACK, "back")
            delay(400)
            backend.key(android.view.KeyEvent.KEYCODE_BACK, "back")
            metrics["tapTarget"] = "search (${search.strategy})"
        } else {
            val tab = Locator.find(screen, WA_TAB)
            if (tab != null) {
                val before = ScreenFingerprint.of(screen)
                backend.perform(UiOp.Tap(tab.element))
                tapOk = waitChange(backend, before) != ScreenChange.NONE
                metrics["tapTarget"] = "tab (${tab.strategy})"
                backend.snapshot()?.let { cur -> Locator.find(cur, WA_CHATS_TAB)?.let { backend.perform(UiOp.Tap(it.element)) } }
            } else metrics["tapTarget"] = "no safe target found"
        }
        metrics["tap"] = tapOk.toString()
        metrics["typeArabic"] = typeOk?.toString() ?: "not tried"
        merge(backend)
        val ok = (scrollable == null || scrollOk) && tapOk && typeOk != false
        return HiddenCheck("WA", "WhatsApp interactions", if (ok) "PASS" else "FAIL",
            "scroll=${metrics["scroll"]}, tap=${metrics["tap"]} (${metrics["tapTarget"]}), type=${metrics["typeArabic"]}", metrics)
    }

    // ---------------- H-8 / H-9 / H-10 ----------------

    private suspend fun concurrentUse(host: IHiddenDisplayHost): Unit = coroutineScope {
        progress("H-8: استخدام الهاتف أثناء عمل المهمة")
        val s = HiddenSession.create(context, host).getOrNull()
            ?: return@coroutineScope run { checks += HiddenCheck("H-8", "use the phone normally during a task", "FAIL", "could not create a display") }
        val backend = HiddenDisplayBackend(service, s)
        if (!openFixture(backend)) {
            checks += HiddenCheck("H-8", "use the phone normally during a task", "FAIL", "test chat did not open on the hidden display")
            s.release(); return@coroutineScope
        }
        val loop = async { typingLoop(backend, 14_000) }
        delay(1_500)
        // Meanwhile on the main screen: open Settings, scroll it, come back.
        monitor.expectUnchanged = false
        val main = mainScreenUse()
        monitor.expectUnchanged = true
        val l = loop.await()
        merge(backend)
        s.release()
        val ok = main.first && l.failed == 0 && l.ok > 0 && l.mainIntrusions == 0
        checks += HiddenCheck("H-8", "use the phone normally during a task", if (ok) "PASS" else "FAIL",
            "main screen: ${main.second}; hidden loop: ${l.ok} ok, ${l.failed} failed, avg ${l.avgMs} ms, p95 ${l.p95Ms} ms",
            mapOf("hiddenOps" to l.ok.toString(), "mainIntrusions" to l.mainIntrusions.toString(), "manual" to "confirm by hand with the 60-second test"))
    }

    private suspend fun stopTest(host: IHiddenDisplayHost): Unit = coroutineScope {
        progress("H-9: الإيقاف الفوري")
        val s = HiddenSession.create(context, host).getOrNull()
            ?: return@coroutineScope run { checks += HiddenCheck("H-9", "immediate stop", "FAIL", "could not create a display") }
        val backend = HiddenDisplayBackend(service, s)
        if (!openFixture(backend)) {
            checks += HiddenCheck("H-9", "immediate stop", "FAIL", "test chat did not open"); s.release(); return@coroutineScope
        }
        val loop = async { typingLoop(backend, 20_000) }
        delay(2_000)
        service.stopSignal.stop("test: stop button")
        val l = loop.await()
        val stopMs = l.stopLatencyMs
        s.release()
        val gone = waitDisplayGone(s.displayId)
        val mainTop = service.mainDisplayState().topPackage
        service.stopSignal.reset()
        val ok = stopMs in 0..1500 && gone && mainTop != FIXTURE
        checks += HiddenCheck("H-9", "immediate stop", if (ok) "PASS" else "FAIL",
            "stopped ${stopMs} ms after the request, display removed=$gone, test app moved to main screen=${mainTop == FIXTURE}",
            mapOf("stopLatencyMs" to stopMs.toString(), "manual" to "also press the stop button / volume-down during the 60-second test"))
        service.beginHiddenTask()
    }

    private suspend fun lossTest(host: IHiddenDisplayHost): Unit = coroutineScope {
        progress("H-10: فقدان صلاحية Shizuku وإعادة الاتصال")
        val s = HiddenSession.create(context, host).getOrNull()
            ?: return@coroutineScope run { checks += HiddenCheck("H-10", "losing Shizuku and reconnecting", "FAIL", "could not create a display") }
        val backend = HiddenDisplayBackend(service, s)
        if (!openFixture(backend)) {
            checks += HiddenCheck("H-10", "losing Shizuku and reconnecting", "FAIL", "test chat did not open"); s.release(); return@coroutineScope
        }
        hostStats(s, "beforeLoss")
        var lostAtNs = 0L
        val listener: (String) -> Unit = { why -> lostAtNs = System.nanoTime(); service.stopSignal.stop("host lost: $why") }
        HiddenDisplayManager.addLostListener(listener)
        val loop = async { typingLoop(backend, 20_000) }
        delay(2_000)
        val t0 = System.nanoTime()
        lossDriver.loseHost()
        val l = loop.await()
        HiddenDisplayManager.removeLostListener(listener)
        val detectMs = if (lostAtNs > 0) (lostAtNs - t0) / 1_000_000 else -1
        val gone = waitDisplayGone(s.displayId)
        val mainTop = service.mainDisplayState().topPackage
        service.stopSignal.reset()
        service.beginHiddenTask()

        val r0 = System.nanoTime()
        val newHost = lossDriver.restoreHost()
        val reconnectMs = (System.nanoTime() - r0) / 1_000_000
        var resumed = false
        if (newHost != null) {
            HiddenSession.create(context, newHost).getOrNull()?.let { s2 ->
                resumed = openFixture(HiddenDisplayBackend(service, s2))
                s2.release()
            }
        }
        val ok = detectMs in 0..3000 && l.stopLatencyMs in 0..2000 && gone && mainTop != FIXTURE && resumed
        checks += HiddenCheck("H-10", "losing Shizuku and reconnecting", if (ok) "PASS" else "FAIL",
            "loss detected after ${detectMs} ms, task stopped ${l.stopLatencyMs} ms later, display removed=$gone, test app moved to main=${mainTop == FIXTURE}, reconnected in ${reconnectMs} ms, resumed=$resumed",
            mapOf("how" to lossDriver.description, "manual" to "also stop Shizuku from its own app during the 60-second test"))
    }

    // ---------------- helpers ----------------

    data class LoopResult(val ok: Int, val failed: Int, val avgMs: Long, val p95Ms: Long, val stopLatencyMs: Long, val mainIntrusions: Int)

    /** Types numbered Arabic text into the test chat field and verifies it, until time runs out or a stop arrives. */
    private suspend fun typingLoop(backend: HiddenDisplayBackend, durationMs: Long): LoopResult {
        val times = mutableListOf<Long>()
        var failed = 0
        var intrusions = 0
        val end = System.currentTimeMillis() + durationMs
        var n = 0
        while (System.currentTimeMillis() < end && !service.stopSignal.stopRequested) {
            n++
            val t = System.nanoTime()
            val snap = backend.snapshot()
            val field = snap?.let { Locator.find(it, ChatProfile.FIXTURE.input) }
            if (service.stopSignal.stopRequested) break
            val ok = if (field != null) {
                val text = "تجربة $n"
                backend.perform(UiOp.SetText(field.element, text)).accepted &&
                    pollCheck(backend, Verification.FieldContains(ChatProfile.FIXTURE.input, text), 1_500)
            } else false
            if (service.stopSignal.stopRequested) break
            if (ok) times += (System.nanoTime() - t) / 1_000_000 else failed++
            if (service.mainDisplayState().topPackage == FIXTURE) intrusions++
            delay(150)
        }
        val stopLatency = if (service.stopSignal.stopRequested && service.stopSignal.requestedAtNanos > 0)
            (System.nanoTime() - service.stopSignal.requestedAtNanos) / 1_000_000 else -1
        val sorted = times.sorted()
        return LoopResult(times.size, failed, if (sorted.isEmpty()) 0 else sorted.average().toLong(),
            if (sorted.isEmpty()) 0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)], stopLatency, intrusions)
    }

    /** Opens Settings on the main screen, scrolls it, and returns to GENESIS. */
    private suspend fun mainScreenUse(): Pair<Boolean, String> {
        val settingsPkg = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName ?: "com.android.settings"
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var opened = false
        repeat(40) {
            if (service.mainDisplayState().topPackage == settingsPkg) { opened = true; return@repeat }
            delay(200)
        }
        if (!opened) return false to "Settings did not open on the main screen"
        delay(600)
        val dm = context.resources.displayMetrics
        val root = service.rootOnDisplay(android.view.Display.DEFAULT_DISPLAY)
        val before = root?.let { ScreenFingerprint.of(NodeMapper.snapshot(it, dm.widthPixels, dm.heightPixels, 0)) }
        val scroller = root?.let { NodeMapper.firstScrollable(it) }
        val scrolled = scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        delay(700)
        val after = service.rootOnDisplay(android.view.Display.DEFAULT_DISPLAY)?.let { ScreenFingerprint.of(NodeMapper.snapshot(it, dm.widthPixels, dm.heightPixels, 0)) }
        val moved = before != null && after != null && before.compare(after) != ScreenChange.NONE
        context.startActivity(Intent(context, com.genesis.app.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        delay(800)
        return (scrolled && moved) to "opened Settings, scrolled=$scrolled, content moved=$moved"
    }

    private suspend fun openFixture(backend: HiddenDisplayBackend): Boolean {
        if (!backend.perform(UiOp.LaunchApp(FIXTURE)).accepted) return false
        return pollCheck(backend, Verification.ElementPresent(ChatProfile.FIXTURE.input), 10_000)
    }

    private fun fixtureTask(): UiTask {
        val send = ChatRecipes.sendInOpenChat("h2-send-${System.currentTimeMillis()}", ChatProfile.FIXTURE, FIXTURE, "Ahmad (test app)",
            "رسالة تجريبية من الشاشة المخفية", "hidden-fixture-${System.currentTimeMillis()}")
        return UiTask("h2-${System.currentTimeMillis()}", "H-2 test chat on the hidden display", setOf(FIXTURE),
            listOf<Step>(Step.Launch(FIXTURE)) +
                Step.WaitFor(Verification.ElementPresent(ChatProfile.FIXTURE.input), "test chat readable on the hidden display", 10_000) +
                Step.Check(Verification.TextShown("هل أنت متاح غداً؟"), "incoming message read from the hidden display") +
                send.steps +
                Step.Check(Verification.ElementPresent(Selector(texts = listOf("sent=1"))), "the test app counted exactly one send"))
    }

    private suspend fun runTask(backend: HiddenDisplayBackend, task: UiTask): RunReport {
        // Approval is automatic only for the separate test chat app, which sends nothing outside the phone.
        val gate = ApprovalGate { r -> if (r.app == FIXTURE) ApprovalDecision.Approved else ApprovalDecision.Rejected }
        val runner = UiRunner(backend, SystemEngineClock, service.stopSignal, gate, EffectLedger(InMemoryEffectStore(), SystemEngineClock), Pacer(SystemEngineClock))
        val r = runner.run(task)
        runs += r
        merge(backend)
        return r
    }

    private fun merge(b: HiddenDisplayBackend) {
        b.paths.forEach { (k, v) -> paths.merge(k, v, Int::plus) }
        b.paths.clear()
    }

    private suspend fun waitChange(backend: HiddenDisplayBackend, before: ScreenFingerprint): ScreenChange {
        repeat(12) {
            delay(250)
            val s = backend.snapshot() ?: return@repeat
            val c = before.compare(ScreenFingerprint.of(s))
            if (c != ScreenChange.NONE) return c
        }
        return ScreenChange.NONE
    }

    private suspend fun pollCheck(backend: HiddenDisplayBackend, v: Verification, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            backend.snapshot()?.let { if (v.check(it)) return true }
            delay(150)
        }
        return false
    }

    private suspend fun pollFind(backend: HiddenDisplayBackend, sel: Selector, timeoutMs: Long): Pair<ScreenSnapshot, com.genesis.automation.ui.LocateResult>? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            backend.snapshot()?.let { s -> Locator.find(s, sel)?.let { return s to it } }
            delay(200)
        }
        return null
    }

    private suspend fun waitDisplayGone(id: Int): Boolean {
        repeat(20) {
            if (displayManager.getDisplay(id) == null) return true
            delay(150)
        }
        return false
    }

    private fun hostStats(s: HiddenSession, label: String): String {
        val st = runCatching { org.json.JSONObject(s.host.stats()) }.getOrNull() ?: return "?"
        resources["host.$label"] = "cpuMs=${st.optLong("cpuMs")}, rssKb=${st.optLong("rssKb")}, frames=${st.optLong("frames")}, uid=${st.optInt("uid")}, lastError=${st.opt("lastError")}"
        return st.optLong("frames").toString()
    }

    /** True when the hidden display's picture is all black (a protected window) while the tree is readable. */
    private fun previewBlank(s: HiddenSession): Boolean? {
        val bytes = runCatching { s.host.thumbnail(120) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var sum = 0L
        var n = 0
        for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
            val p = bmp.getPixel(x, y)
            sum += ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff); n++
        }
        return n > 0 && sum / (3 * n) < 6
    }

    private fun battery(): Int =
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    /** Samples what the main screen shows every 250 ms. */
    class MainMonitor(private val service: GenesisAccessibilityService) {
        @Volatile var expectUnchanged = true
        private var baseline: String? = null
        private val tops = linkedMapOf<String, Int>()
        private val foreign = linkedSetOf<String>()
        private var ime = 0
        private var samples = 0

        data class Summary(val samples: Int, val tops: Map<String, Int>, val foreignTops: Set<String>, val imeSamples: Int)

        fun start(scope: CoroutineScope): Job {
            baseline = service.mainDisplayState().topPackage
            return scope.launch {
                while (isActive) {
                    if (expectUnchanged) {
                        val st = service.mainDisplayState()
                        synchronized(this@MainMonitor) {
                            samples++
                            val top = st.topPackage ?: "none"
                            tops.merge(top, 1, Int::plus)
                            if (st.topPackage != null && st.topPackage != baseline) foreign += top
                            if (st.imeVisible) ime++
                        }
                    }
                    delay(250)
                }
            }
        }

        @Synchronized fun snapshot() = Summary(samples, tops.toMap(), foreign.toSet(), ime)
    }

    companion object {
        const val FIXTURE = "com.genesis.fixture.chat"
        const val WHATSAPP = "com.whatsapp"
        private const val TEST_TEXT = "اختبار GENESIS"
        private val WA_SEARCH = Selector(
            resourceIds = listOf("com.whatsapp:id/menuitem_search", "com.whatsapp:id/my_search_bar", "com.whatsapp:id/search_bar", "com.whatsapp:id/search_bar_inner_layout"),
            // Exact labels only: a looser match could hit a chat row and open a real conversation.
            descriptions = listOf("Search", "بحث"),
        )
        private val WA_TAB = Selector(texts = listOf("Calls", "المكالمات", "Updates", "المستجدات"), descriptions = listOf("Calls", "المكالمات", "Updates", "المستجدات"))
        private val WA_CHATS_TAB = Selector(texts = listOf("Chats", "الدردشات", "المحادثات"), descriptions = listOf("Chats", "الدردشات", "المحادثات"))
    }
}
