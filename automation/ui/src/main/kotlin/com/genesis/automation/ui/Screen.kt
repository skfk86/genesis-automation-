package com.genesis.automation.ui

import kotlinx.serialization.Serializable

/** Pixel rectangle on screen. */
@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** What kind of control an element is, normalised across backends. */
@Serializable
enum class Role { BUTTON, TEXT_FIELD, TEXT, IMAGE, LIST, CHECKBOX, SWITCH, CONTAINER, OTHER }

/**
 * One element of the screen, as read by a backend (accessibility tree, uiautomator dump, ...).
 * [ref] is only stable inside the snapshot it came from.
 */
@Serializable
data class UiElement(
    val ref: Int,
    val role: Role,
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val password: Boolean = false,
    val depth: Int = 0,
) {
    /** Text shown to the user, never the content of a password field. */
    val visibleLabel: String?
        get() = if (password) null else (text?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() })
}

/** Everything the engine knows about the screen at one moment. */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val elements: List<UiElement>,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtMillis: Long,
    /** The window is protected (FLAG_SECURE) or otherwise sensitive. The engine must not act in it. */
    val secure: Boolean = false,
    val windowTitle: String? = null,
) {
    fun element(ref: Int): UiElement? = elements.firstOrNull { it.ref == ref }

    /** Elements a person could see and use: on screen, non-empty. */
    val visibleElements: List<UiElement>
        get() = elements.filter { !it.bounds.isEmpty && it.bounds.right > 0 && it.bounds.left < screenWidth }
}
