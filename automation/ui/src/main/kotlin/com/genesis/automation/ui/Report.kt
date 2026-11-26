package com.genesis.automation.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class StepOutcome { OK, FAILED, DENIED, STOPPED, NEEDS_USER, SKIPPED_ALREADY_DONE, REJECTED_BY_USER }

@Serializable
enum class RunResult { SUCCESS, FAILED, STOPPED, NEEDS_USER, REJECTED_BY_USER }

/** One line of the step log. Never contains typed or sent text, only lengths and labels. */
@Serializable
data class StepLog(
    val index: Int,
    val step: String,
    val label: String,
    val outcome: StepOutcome,
    val detail: String,
    val durationMillis: Long,
    val attempts: Int = 1,
    val matchStrategy: MatchStrategy? = null,
    val uiChangeDetected: Boolean = false,
    val screenChange: ScreenChange? = null,
)

@Serializable
data class RunReport(
    val taskId: String,
    val taskName: String,
    val backend: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val result: RunResult,
    val summary: String,
    val steps: List<StepLog>,
    val effects: List<EffectRecord>,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}
