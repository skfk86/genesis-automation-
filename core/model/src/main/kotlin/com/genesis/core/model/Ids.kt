package com.genesis.core.model

import kotlinx.serialization.Serializable

/** Stable identifier of a registered action in the action catalog, e.g. `app.launch`. */
@Serializable
@JvmInline
value class ActionId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid action id: '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    }
}

@Serializable
@JvmInline
value class MissionId(val value: String) {
    init {
        require(value.isNotBlank()) { "MissionId must not be blank" }
    }

    override fun toString(): String = value
}
