package com.genesis.automation.ui

import kotlinx.serialization.Serializable

/**
 * Lifecycle of one external effect (sending a message, publishing a post).
 *
 * The rule that prevents duplicates: an effect is attempted automatically at most once per
 * proof of non-delivery. After an attempt, it is retried only when the screen positively shows
 * it did not happen ([NOT_DELIVERED]). When the outcome is unclear it becomes [UNKNOWN] and
 * the engine stops and asks the user. It never "tries again to be safe".
 */
@Serializable
enum class EffectState { APPROVED, ATTEMPTED, VERIFIED, NOT_DELIVERED, UNKNOWN, REJECTED }

@Serializable
data class EffectRecord(
    val key: String,
    val description: String,
    val state: EffectState,
    val attempts: Int,
    val updatedAtMillis: Long,
    val note: String = "",
)

/** Persists effect records. The app stores them on disk so a crash cannot cause a second send. */
interface EffectStore {
    fun get(key: String): EffectRecord?
    fun put(record: EffectRecord)
}

class InMemoryEffectStore : EffectStore {
    private val map = LinkedHashMap<String, EffectRecord>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(record: EffectRecord) { map[record.key] = record }
    @Synchronized fun all(): List<EffectRecord> = map.values.toList()
}

sealed interface EffectGate {
    /** Never attempted: go ahead (after approval). */
    data object Fresh : EffectGate

    /** Already confirmed. Do not repeat. */
    data object AlreadyDone : EffectGate

    /** Shown not to have happened; one more attempt is allowed. */
    data class RetryAllowed(val attemptsSoFar: Int) : EffectGate

    /** Outcome unclear, rejected, or too many attempts. A person must decide. */
    data class Blocked(val why: String) : EffectGate
}

class EffectLedger(private val store: EffectStore, private val clock: EngineClock, private val maxAttempts: Int = 2) {

    fun gate(key: String): EffectGate {
        val r = store.get(key) ?: return EffectGate.Fresh
        return when (r.state) {
            EffectState.VERIFIED -> EffectGate.AlreadyDone
            EffectState.APPROVED -> if (r.attempts == 0) EffectGate.Fresh else EffectGate.Blocked("approved record with attempts")
            EffectState.NOT_DELIVERED ->
                if (r.attempts < maxAttempts) EffectGate.RetryAllowed(r.attempts)
                else EffectGate.Blocked("not delivered after ${r.attempts} attempts")
            EffectState.ATTEMPTED -> EffectGate.Blocked("a previous attempt has no recorded result (possible crash); check the screen manually")
            EffectState.UNKNOWN -> EffectGate.Blocked("outcome unknown: ${r.note}")
            EffectState.REJECTED -> EffectGate.Blocked("rejected by the user")
        }
    }

    fun record(key: String): EffectRecord? = store.get(key)

    fun mark(key: String, description: String, state: EffectState, note: String = "", countAttempt: Boolean = false) {
        val prev = store.get(key)
        val attempts = (prev?.attempts ?: 0) + if (countAttempt) 1 else 0
        store.put(EffectRecord(key, description, state, attempts, clock.nowMillis(), note))
    }
}
