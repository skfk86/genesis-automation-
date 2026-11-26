package com.genesis.core.model

import kotlinx.serialization.Serializable

/**
 * What is actually known about one action execution. These are deliberately distinct:
 * attempting an action is not the same as the system accepting it, the action completing,
 * or GENESIS independently verifying the result.
 */
@Serializable
sealed interface ExecutionOutcome {
    /** Not started yet. */
    @Serializable
    data object Pending : ExecutionOutcome

    /** GENESIS started the action; no response from the system yet. */
    @Serializable
    data class Attempted(val atEpochMillis: Long) : ExecutionOutcome

    /** The system accepted the request (e.g. an intent resolved and the activity started). */
    @Serializable
    data class Accepted(val atEpochMillis: Long, val detail: String) : ExecutionOutcome

    /** The action reports completion, but GENESIS has not independently confirmed the effect. */
    @Serializable
    data class Completed(val atEpochMillis: Long, val detail: String) : ExecutionOutcome

    /** The effect was independently confirmed. [evidence] says how. */
    @Serializable
    data class Verified(val atEpochMillis: Long, val evidence: String) : ExecutionOutcome

    @Serializable
    data class Failed(val atEpochMillis: Long, val reason: String, val retryable: Boolean) : ExecutionOutcome

    /** The user (or the platform) declined, e.g. backed out of the share sheet. */
    @Serializable
    data class Declined(val atEpochMillis: Long, val reason: String) : ExecutionOutcome

    /**
     * The process died after the action was attempted and before its result was recorded.
     * The effect may or may not have happened.
     */
    @Serializable
    data class Unknown(val atEpochMillis: Long, val lastKnown: String) : ExecutionOutcome
}

/** True when the effect may already have happened in the outside world. */
val ExecutionOutcome.mayHaveTakenEffect: Boolean
    get() = when (this) {
        is ExecutionOutcome.Attempted,
        is ExecutionOutcome.Accepted,
        is ExecutionOutcome.Completed,
        is ExecutionOutcome.Verified,
        is ExecutionOutcome.Unknown -> true
        ExecutionOutcome.Pending,
        is ExecutionOutcome.Failed,
        is ExecutionOutcome.Declined -> false
    }
