package com.genesis.automation.ui

import com.genesis.core.model.RiskLevel

/** Counts how many times an effect is visible on screen (for example: outgoing bubbles with this text). */
interface EffectProof {
    val label: String
    fun count(s: ScreenSnapshot): Int
}

/** One step of a UI task. Steps only describe intent; the runner decides how to carry them out. */
sealed interface Step {
    val label: String

    data class Launch(val packageName: String, override val label: String = "open $packageName") : Step

    data class Tap(val target: Selector, override val label: String) : Step

    data class TypeText(val field: Selector, val text: String, override val label: String) : Step

    /** Scroll a container until [target] is visible. */
    data class ScrollTo(
        val target: Selector,
        override val label: String,
        val container: Selector? = null,
        val maxScrolls: Int = 12,
        val direction: ScrollDirection = ScrollDirection.FORWARD,
    ) : Step

    data class WaitFor(val condition: Verification, override val label: String, val timeoutMillis: Long = 6_000) : Step

    data class Check(val condition: Verification, override val label: String) : Step

    /**
     * A step that changes the outside world, such as pressing Send.
     *
     * Before acting the user approves [content]. After acting, success means [proof] counts one
     * more than before. A retry happens only when [notDeliveredEvidence] holds and the count did
     * not change. Anything else stops the task as unknown.
     */
    data class Effect(
        val key: String,
        val action: Selector,
        val app: String,
        val title: String,
        val content: String,
        val proof: EffectProof,
        val notDeliveredEvidence: Verification,
        val risk: RiskLevel = RiskLevel.HIGH,
        override val label: String,
        val confirmTimeoutMillis: Long = 5_000,
    ) : Step
}

data class UiTask(val id: String, val name: String, val allowedPackages: Set<String>, val steps: List<Step>)
