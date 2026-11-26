package com.genesis.app.hidden

import android.content.Context
import android.os.Debug
import android.os.Process
import com.genesis.app.engine.Engine
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.automation.ui.ChatProfile
import com.genesis.automation.ui.Locator
import com.genesis.automation.ui.ScrollDirection
import com.genesis.automation.ui.UiOp
import com.genesis.automation.ui.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The hand test: a task runs on the hidden display for [durationMs] while the person uses the
 * phone, presses stop, or stops Shizuku. Records what happened; no app content is stored.
 *
 * Target "fixture" types numbered text into the test chat field and checks it. Target "whatsapp"
 * only reads WhatsApp and scrolls its list (nothing is typed or sent).
 */
class HiddenSoak(
    private val context: Context,
    private val service: GenesisAccessibilityService,
    private val target: String,
    private val durationMs: Long = 60_000,
    private val progress: (String) -> Unit = {},
    private val preview: (ByteArray) -> Unit = {},
) {
    suspend fun run(): HiddenSuiteReport = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val cpu0 = Process.getElapsedCpuTime()
        val battery0 = battery()
        val mainTops = linkedMapOf<String, Int>()
        var imeOnMain = 0
        var ok = 0
        var failed = 0
        var losses = 0
        var recoveries = 0
        val recoverMs = mutableListOf<Long>()
        var stopReason: String? = null
        var stopLatency = -1L
        val hostSamples = mutableListOf<String>()
        var lostFlag = false
        val listener: (String) -> Unit = { lostFlag = true }
        HiddenDisplayManager.addLostListener(listener)
        service.beginHiddenTask()
        var session: HiddenSession? = null
        var backend: HiddenDisplayBackend? = null
        try {
            val end = System.currentTimeMillis() + durationMs
            var n = 0
            while (System.currentTimeMillis() < end) {
                if (service.stopSignal.stopRequested) {
                    stopReason = service.stopSignal.reason
                    stopLatency = (System.nanoTime() - service.stopSignal.requestedAtNanos) / 1_000_000
                    break
                }
                if (lostFlag) {
                    lostFlag = false
                    losses++
                    session = null
                    backend = null
                    progress("فُقد الاتصال بـ Shizuku. أعد تشغيله وسيعود GENESIS تلقائياً…")
                }
                if (session == null) {
                    val lostAt = System.currentTimeMillis()
                    val host = waitHost(end) ?: break
                    val s = HiddenSession.create(context, host).getOrNull()
                    if (s == null) { failed++; delay(1000); continue }
                    session = s
                    backend = HiddenDisplayBackend(service, s)
                    if (!open(backend)) { failed++; s.release(); session = null; delay(1000); continue }
                    if (losses > 0) { recoveries++; recoverMs += System.currentTimeMillis() - lostAt }
                }
                n++
                val b = backend!!
                val good = if (target == SOAK_WHATSAPP) readAndScroll(b, n) else typeOnce(b, n)
                if (good) ok++ else failed++
                val main = service.mainDisplayState()
                mainTops.merge(main.topPackage ?: "none", 1, Int::plus)
                if (main.imeVisible) imeOnMain++
                if (n % 5 == 0) {
                    session?.let { s ->
                        runCatching { s.host.thumbnail(360) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(preview)
                        runCatching { JSONObject(s.host.stats()) }.getOrNull()?.let { hostSamples += "cpuMs=${it.optLong("cpuMs")} rssKb=${it.optLong("rssKb")} frames=${it.optLong("frames")}" }
                    }
                    progress("يعمل في الخلفية: $ok عملية ناجحة، $failed فاشلة")
                }
                delay(400)
            }
        } finally {
            HiddenDisplayManager.removeLostListener(listener)
            session?.release()
            service.endTask()
        }
        val status = when {
            ok == 0 -> "FAIL"
            failed > 0 && losses == 0 -> "FAIL"
            else -> "PASS"
        }
        val check = HiddenCheck("M-60", "60-second hand test on the hidden display ($target)", status,
            "ok=$ok, failed=$failed, stop=${stopReason ?: "time up"}${if (stopLatency >= 0) " after $stopLatency ms" else ""}, " +
                "losses=$losses, recoveries=$recoveries ${recoverMs}, main-screen apps the person used=${mainTops.keys}, keyboard on main=$imeOnMain",
            mapOf(
                "mainTops" to mainTops.toString(),
                "stopLatencyMs" to stopLatency.toString(),
                "hostSamples" to hostSamples.takeLast(4).toString(),
            ))
        HiddenSuiteReport(
            kind = "hidden-display-hand-test",
            device = Engine.deviceInfo(context),
            hostMode = HiddenDisplayManager.mode,
            shizukuVersion = HiddenDisplayManager.shizukuVersion(),
            startedAtMillis = started,
            finishedAtMillis = System.currentTimeMillis(),
            checks = listOf(check),
            runs = emptyList(),
            actionPaths = backend?.paths?.toMap() ?: emptyMap(),
            resources = mapOf(
                "durationMs" to (System.currentTimeMillis() - started).toString(),
                "appCpuMs" to (Process.getElapsedCpuTime() - cpu0).toString(),
                "appPssKb" to Debug.getPss().toString(),
                "batteryStartPct" to battery0.toString(),
                "batteryEndPct" to battery().toString(),
            ),
            events = HiddenDisplayManager.events.toList(),
        ).also { HiddenSuiteReport.save(context, it) }
    }

    private suspend fun waitHost(deadline: Long): IHiddenDisplayHost? {
        while (System.currentTimeMillis() < deadline && !service.stopSignal.stopRequested) {
            HiddenDisplayManager.host?.let { return it }
            HiddenDisplayManager.refresh()
            delay(500)
        }
        return null
    }

    private suspend fun open(b: HiddenDisplayBackend): Boolean {
        val pkg = if (target == SOAK_WHATSAPP) HiddenDisplaySuite.WHATSAPP else HiddenDisplaySuite.FIXTURE
        if (!b.perform(UiOp.LaunchApp(pkg)).accepted) return false
        repeat(40) {
            val s = b.snapshot()
            if (s != null && s.packageName == pkg && s.elements.size > 2) return true
            delay(250)
        }
        return false
    }

    private suspend fun typeOnce(b: HiddenDisplayBackend, n: Int): Boolean {
        val s = b.snapshot() ?: return false
        val field = Locator.find(s, ChatProfile.FIXTURE.input) ?: return false
        val text = "تجربة $n"
        if (!b.perform(UiOp.SetText(field.element, text)).accepted) return false
        repeat(10) {
            b.snapshot()?.let { if (Verification.FieldContains(ChatProfile.FIXTURE.input, text).check(it)) return true }
            delay(150)
        }
        return false
    }

    private suspend fun readAndScroll(b: HiddenDisplayBackend, n: Int): Boolean {
        val s = b.snapshot() ?: return false
        if (s.packageName != HiddenDisplaySuite.WHATSAPP) return false
        val list = s.visibleElements.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height } ?: return true
        return b.perform(UiOp.Scroll(list, if (n % 2 == 0) ScrollDirection.BACKWARD else ScrollDirection.FORWARD)).accepted
    }

    private fun battery(): Int =
        context.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    companion object {
        const val SOAK_FIXTURE = "fixture"
        const val SOAK_WHATSAPP = "whatsapp"
    }
}
