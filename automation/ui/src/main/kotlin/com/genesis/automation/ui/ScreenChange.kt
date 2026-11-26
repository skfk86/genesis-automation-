package com.genesis.automation.ui

import kotlinx.serialization.Serializable

/** How the screen differs between two snapshots. */
@Serializable
enum class ScreenChange {
    /** Nothing a person would notice. */
    NONE,

    /** Same layout, different text (a new message arrived, a field was filled). */
    CONTENT,

    /** Different layout: another screen of the same app, or the app's UI changed. */
    STRUCTURE,

    /** Another app is in front. */
    APP,
}

/**
 * Compact signatures of a screen. The structure signature ignores text, so typing or a new
 * message does not count as a layout change; the content signature includes it.
 */
data class ScreenFingerprint(val packageName: String, val structure: Int, val content: Int) {

    fun compare(other: ScreenFingerprint): ScreenChange = when {
        packageName != other.packageName -> ScreenChange.APP
        structure != other.structure -> ScreenChange.STRUCTURE
        content != other.content -> ScreenChange.CONTENT
        else -> ScreenChange.NONE
    }

    companion object {
        fun of(s: ScreenSnapshot): ScreenFingerprint {
            val visible = s.visibleElements
            // Repeated rows (messages in a list) collapse to one entry each, so a new message
            // in an existing conversation is a content change, not a structure change.
            val structure = visible
                .map { "${it.resourceId}|${it.role}|${it.className}|${it.editable}|${it.scrollable}" }
                .toSortedSet()
                .hashCode()
            val content = visible.map { "${it.resourceId}|${if (it.password) "" else it.text}|${it.description}" }.hashCode()
            return ScreenFingerprint(s.packageName, structure, content)
        }
    }
}
