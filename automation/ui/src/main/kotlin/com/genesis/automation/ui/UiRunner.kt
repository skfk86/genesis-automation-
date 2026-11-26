package com.genesis.automation.ui

/**
 * Suggests other ways to find an element when the expected one is missing (the app's UI
 * changed). The prototype uses [HeuristicReplanner]; a model-backed replanner that picks a
 * numbered element can be plugged in later without touching the runner.
 */
fun interface Replanner {
    suspend fun alternatives(stepLabel: String, selector: Selector, screen: ScreenSnapshot): List<Selector>
}

/** Relaxes a selector step by step: without ids, then by text fragments, then by role alone. */
object HeuristicReplanner : Replanner {
    override suspend fun alternatives(stepLabel: String, selector: Selector, screen: ScreenSnapshot): List<Selector> {
        val out = mutableListOf<Selector>()
        val words = (selector.descriptions + selector.texts).distinct()
        if (selector.resourceIds.isNotEmpty() && (words.isNotEmpty() || selector.roles.isNotEmpty() || selector.editable != null)) {
            out += selector.copy(resourceIds = emptyList())
        }
        if (words.isNotEmpty()) {
            out += Selector(textContains = words, roles = selector.roles, editable = selector.editable, region = selector.region)
        }
        if (selector.roles.isNotEmpty() && !selector.allowRoleOnly && selector.editable == true) {
            out += Selector(roles = selector.roles, editable = true, region = selector.region, allowRoleOnly = true)
        }
        return out
    }
}

private class StopTask(val outcome: StepOutcome, val result: RunResult, message: String) : Exception(message)

/**
 * Runs a [UiTask] as observe -> locate -> guard -> act -> verify -> replan.
 *
 * Guarantees, each covered by tests:
 * - every external effect asks [approval] first;
 * - an external effect is never repeated unless the screen proves it did not happen;
 * - a changed UI is detected (the expected id is missing) and handled by fallbacks;
 * - [stop] ends the task between any two operations.
 */
class UiRunner(
    private val backend: UiBackend,
    private val clock: EngineClock,
    private val stop: StopSignal,
    private val approval: ApprovalGate,
    private val ledger: EffectLedger,
    private val pacer: Pacer,
    private val replanner: Replanner = HeuristicReplanner,
    private val locateTimeoutMillis: Long = 4_000,
    private val pollMillis: Long = 250,
    private val settleTimeoutMillis: Long = 1_500,
) {
    private lateinit var safety: SafetyPolicy
    private val logs = mutableListOf<StepLog>()

    suspend fun run(task: UiTask): RunReport {
        safety = SafetyPolicy(task.allowedPackages)
        logs.clear()
        val started = clock.nowMillis()
        var result = RunResult.SUCCESS
        var summary = "all ${task.steps.size} steps completed"
        for ((i, step) in task.steps.withIndex()) {
            val t0 = clock.nowMillis()
            try {
                checkStop()
                execute(i, step, t0)
            } catch (e: StopTask) {
                logs += StepLog(i, step.kind(), step.label, e.outcome, e.message.orEmpty(), clock.nowMillis() - t0)
                result = e.result
                summary = "step ${i + 1} (${step.label}): ${e.message}"
                break
            }
        }
        val effects = task.steps.filterIsInstance<Step.Effect>().mapNotNull { ledger.record(it.key) }
        return RunReport(task.id, task.name, backend.id, started, clock.nowMillis(), result, summary, logs.toList(), effects)
    }

    private suspend fun execute(i: Int, step: Step, t0: Long) {
        when (step) {
            is Step.Launch -> {
                act(UiOp.LaunchApp(step.packageName), null)
                val ok = pollUntil(8_000) { Verification.InApp(step.packageName).check(it) }
                if (!ok) fail("app ${step.packageName} did not come to the front")
                ok(i, step, t0, "in ${step.packageName}")
            }

            is Step.Tap -> {
                var attempts = 0
                var last: LocateResult? = null
                var change = ScreenChange.NONE
                while (attempts < 2) {
                    attempts++
                    val (screen, found) = locate(step.label, step.target)
                    last = found
                    change = actAndSettle(UiOp.Tap(found.element), screen)
                    if (change != ScreenChange.NONE) break
                }
                // A tap that changes nothing visible may still be fine (e.g. a toggle off-screen); record it honestly.
                logs += StepLog(i, step.kind(), step.label, StepOutcome.OK,
                    if (change == ScreenChange.NONE) "tapped, no visible change" else "tapped",
                    clock.nowMillis() - t0, attempts, last?.strategy, last?.indicatesUiChange(step.target) == true, change)
            }

            is Step.TypeText -> {
                var attempts = 0
                lateinit var found: LocateResult
                while (true) {
                    attempts++
                    val (screen, f) = locate(step.label, step.field)
                    found = f
                    act(UiOp.SetText(f.element, step.text), screen)
                    val typed = pollUntil(2_000) { Verification.FieldContains(step.field.relaxedTo(f.element), step.text).check(it) }
                    if (typed) break
                    if (attempts >= 2) fail("text did not appear in the field after $attempts attempts")
                }
                logs += StepLog(i, step.kind(), step.label, StepOutcome.OK, "typed ${step.text.length} chars",
                    clock.nowMillis() - t0, attempts, found.strategy, found.indicatesUiChange(step.field))
            }

            is Step.ScrollTo -> {
                var scrolls = 0
                while (true) {
                    val screen = observe()
                    val hit = Locator.find(screen, step.target)
                    if (hit != null) {
                        logs += StepLog(i, step.kind(), step.label, StepOutcome.OK, "found after $scrolls scrolls",
                            clock.nowMillis() - t0, scrolls + 1, hit.strategy, hit.indicatesUiChange(step.target))
                        return
                    }
                    if (scrolls >= step.maxScrolls) fail("not found after $scrolls scrolls")
                    val container = step.container?.let { Locator.find(screen, it)?.element }
                        ?: screen.visibleElements.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
                    val change = actAndSettle(UiOp.Scroll(container, step.direction), screen)
                    scrolls++
                    if (change == ScreenChange.NONE) fail("reached the end of the list without finding it")
                }
            }

            is Step.WaitFor -> {
                if (!pollUntil(step.timeoutMillis) { step.condition.check(it) }) fail("timed out: ${step.condition.label}")
                ok(i, step, t0, step.condition.label)
            }

            is Step.Check -> {
                if (!pollUntil(2_000) { step.condition.check(it) }) fail("check failed: ${step.condition.label}")
                ok(i, step, t0, step.condition.label)
            }

            is Step.Effect -> runEffect(i, step, t0)
        }
    }

    private suspend fun runEffect(i: Int, step: Step.Effect, t0: Long) {
        when (val g = ledger.gate(step.key)) {
            EffectGate.AlreadyDone -> {
                logs += StepLog(i, step.kind(), step.label, StepOutcome.SKIPPED_ALREADY_DONE, "already verified earlier; not repeated", clock.nowMillis() - t0, 0)
                return
            }
            is EffectGate.Blocked -> throw StopTask(StepOutcome.NEEDS_USER, RunResult.NEEDS_USER, g.why)
            is EffectGate.RetryAllowed, EffectGate.Fresh -> Unit
        }
        if (!pacer.tryReserveEffect()) throw StopTask(StepOutcome.NEEDS_USER, RunResult.NEEDS_USER, "safety limit for sends in this period reached")

        val decision = approval.request(ApprovalRequest(step.key, step.app, step.title, step.content, step.risk))
        if (decision == ApprovalDecision.Rejected) {
            ledger.mark(step.key, step.title, EffectState.REJECTED, "user rejected")
            throw StopTask(StepOutcome.REJECTED_BY_USER, RunResult.REJECTED_BY_USER, "user rejected; nothing was sent")
        }
        if (ledger.record(step.key) == null) ledger.mark(step.key, step.title, EffectState.APPROVED)
        checkStop()

        val baseline = step.proof.count(observe())
        while (true) {
            val (screen, found) = locate(step.label, step.action)
            // Record the attempt before acting: if the app dies mid-tap, the next run sees ATTEMPTED and asks.
            ledger.mark(step.key, step.title, EffectState.ATTEMPTED, countAttempt = true)
            act(UiOp.Tap(found.element), screen)

            var after: ScreenSnapshot? = null
            val confirmed = pollUntil(step.confirmTimeoutMillis) { s -> after = s; step.proof.count(s) > baseline }
            val finalScreen = after ?: observe()
            val count = step.proof.count(finalScreen)
            val attempts = ledger.record(step.key)?.attempts ?: 1
            if (confirmed) {
                val note = if (count > baseline + 1) "WARNING: effect visible ${count - baseline} times" else "confirmed on screen (${step.proof.label})"
                ledger.mark(step.key, step.title, EffectState.VERIFIED, note)
                logs += StepLog(i, step.kind(), step.label, StepOutcome.OK, note, clock.nowMillis() - t0, attempts,
                    found.strategy, found.indicatesUiChange(step.action))
                return
            }
            if (count == baseline && step.notDeliveredEvidence.check(finalScreen)) {
                ledger.mark(step.key, step.title, EffectState.NOT_DELIVERED, "screen shows it was not sent")
                when (val g = ledger.gate(step.key)) {
                    is EffectGate.RetryAllowed -> { checkStop(); continue }
                    is EffectGate.Blocked -> throw StopTask(StepOutcome.NEEDS_USER, RunResult.NEEDS_USER, g.why)
                    else -> throw StopTask(StepOutcome.NEEDS_USER, RunResult.NEEDS_USER, "unexpected ledger state")
                }
            }
            ledger.mark(step.key, step.title, EffectState.UNKNOWN, "no confirmation and no proof of non-delivery")
            throw StopTask(StepOutcome.NEEDS_USER, RunResult.NEEDS_USER,
                "could not confirm whether it was sent; not retrying to avoid a duplicate. Please check the app.")
        }
    }

    // ---- building blocks ----

    private suspend fun observe(): ScreenSnapshot {
        repeat(3) {
            backend.snapshot()?.let { return it }
            clock.sleep(pollMillis)
        }
        throw StopTask(StepOutcome.FAILED, RunResult.FAILED, "screen not readable (service off or screen locked)")
    }

    private suspend fun locate(stepLabel: String, selector: Selector): Pair<ScreenSnapshot, LocateResult> {
        var screen = observe()
        val deadline = clock.nowMillis() + locateTimeoutMillis
        while (true) {
            Locator.find(screen, selector)?.let { return screen to it }
            if (clock.nowMillis() >= deadline) break
            checkStop()
            clock.sleep(pollMillis)
            screen = observe()
        }
        for (alt in replanner.alternatives(stepLabel, selector, screen)) {
            Locator.find(screen, alt)?.let { return screen to it }
        }
        fail("element not found: $selector")
    }

    private suspend fun act(op: UiOp, screen: ScreenSnapshot?): OpResult {
        checkStop()
        when (val v = safety.check(op, screen)) {
            is GuardVerdict.Deny -> throw StopTask(StepOutcome.DENIED, RunResult.STOPPED, "blocked by safety rule: ${v.reason}")
            GuardVerdict.Allow -> Unit
        }
        pacer.beforeOp()
        checkStop()
        val r = backend.perform(op)
        if (!r.accepted) fail("system refused ${op.summary}: ${r.detail}")
        return r
    }

    private suspend fun actAndSettle(op: UiOp, before: ScreenSnapshot): ScreenChange {
        val fp = ScreenFingerprint.of(before)
        act(op, before)
        var change = ScreenChange.NONE
        pollUntil(settleTimeoutMillis) { s -> change = fp.compare(ScreenFingerprint.of(s)); change != ScreenChange.NONE }
        return change
    }

    private suspend fun pollUntil(timeoutMillis: Long, cond: (ScreenSnapshot) -> Boolean): Boolean {
        val deadline = clock.nowMillis() + timeoutMillis
        while (true) {
            checkStop()
            val s = backend.snapshot()
            if (s != null && cond(s)) return true
            if (clock.nowMillis() >= deadline) return false
            clock.sleep(pollMillis)
        }
    }

    private fun checkStop() {
        if (stop.stopRequested) throw StopTask(StepOutcome.STOPPED, RunResult.STOPPED, "stopped: ${stop.reason ?: "by user"}")
    }

    private fun fail(why: String): Nothing = throw StopTask(StepOutcome.FAILED, RunResult.FAILED, why)

    private fun ok(i: Int, step: Step, t0: Long, detail: String) {
        logs += StepLog(i, step.kind(), step.label, StepOutcome.OK, detail, clock.nowMillis() - t0)
    }
}

private fun Step.kind(): String = this::class.simpleName ?: "Step"

/** After a fallback found the field, check its text through the element it actually matched. */
private fun Selector.relaxedTo(e: UiElement): Selector =
    if (e.resourceId != null) Selector(resourceIds = listOf(e.resourceId))
    else Selector(roles = setOf(Role.TEXT_FIELD), editable = true, region = region, allowRoleOnly = true)
