package com.genesis.automation.ui

import kotlinx.serialization.Serializable

@Serializable
enum class ScrollDirection { FORWARD, BACKWARD }

/** The fixed set of things the engine can do on screen. Nothing outside this list is possible. */
sealed interface UiOp {
    data class Tap(val target: UiElement) : UiOp
    data class SetText(val target: UiElement, val text: String) : UiOp
    data class Scroll(val container: UiElement?, val direction: ScrollDirection) : UiOp
    data object Back : UiOp
    data class LaunchApp(val packageName: String) : UiOp

    /** Short description for logs. Never includes typed text, which may be private. */
    val summary: String
        get() = when (this) {
            is Tap -> "tap #${target.ref} ${target.visibleLabel ?: target.resourceId ?: target.role}"
            is SetText -> "set_text #${target.ref} (${text.length} chars)"
            is Scroll -> "scroll ${direction.name.lowercase()}"
            Back -> "back"
            is LaunchApp -> "launch $packageName"
        }
}

@Serializable
data class OpResult(val accepted: Boolean, val detail: String = "")

/**
 * A way to read and drive the screen: AccessibilityService (primary), Shizuku (optional),
 * or a fake in tests. Implementations live outside this pure-Kotlin module.
 */
interface UiBackend {
    val id: String

    /** Null when the backend cannot read the screen right now (service off, screen locked). */
    suspend fun snapshot(): ScreenSnapshot?

    suspend fun perform(op: UiOp): OpResult
}

/** Lets tests run without real waiting. */
interface EngineClock {
    fun nowMillis(): Long
    suspend fun sleep(millis: Long)
}

/** Checked between every step. Set by the stop button, by the user touching the screen, or by a guard. */
interface StopSignal {
    val stopRequested: Boolean
    val reason: String?
}

class MutableStopSignal : StopSignal {
    @Volatile override var stopRequested: Boolean = false
        private set
    @Volatile override var reason: String? = null
        private set

    fun stop(why: String) {
        reason = why
        stopRequested = true
    }

    fun reset() {
        stopRequested = false
        reason = null
    }
}
