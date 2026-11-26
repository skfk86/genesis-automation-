package com.genesis.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.genesis.automation.ui.Bounds
import com.genesis.automation.ui.Role
import com.genesis.automation.ui.ScreenSnapshot
import com.genesis.automation.ui.UiElement

/** Converts the accessibility tree into the engine's [ScreenSnapshot]. */
internal object NodeMapper {

    private const val MAX_NODES = 1500

    fun snapshot(root: AccessibilityNodeInfo, width: Int, height: Int, now: Long): ScreenSnapshot {
        val out = ArrayList<UiElement>()
        walk(root, 0) { node, depth ->
            toElement(node, out.size, depth)?.let { out += it }
        }
        return ScreenSnapshot(
            packageName = root.packageName?.toString() ?: "unknown",
            elements = out,
            screenWidth = width,
            screenHeight = height,
            capturedAtMillis = now,
        )
    }

    /** Depth-first walk over visible nodes, bounded so a huge tree cannot stall the engine. */
    fun walk(root: AccessibilityNodeInfo, startDepth: Int, visit: (AccessibilityNodeInfo, Int) -> Unit) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to startDepth)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            seen++
            if (!node.isVisibleToUser) continue
            visit(node, depth)
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
    }

    fun boundsOf(node: AccessibilityNodeInfo): Bounds {
        val r = Rect()
        node.getBoundsInScreen(r)
        return Bounds(r.left, r.top, r.right, r.bottom)
    }

    private fun toElement(n: AccessibilityNodeInfo, ref: Int, depth: Int): UiElement? {
        val text = n.text?.toString()
        val desc = n.contentDescription?.toString()
        val id = n.viewIdResourceName
        val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || id != null ||
            n.isClickable || n.isEditable || n.isScrollable
        if (!interesting) return null
        val b = boundsOf(n)
        if (b.isEmpty) return null
        return UiElement(
            ref = ref,
            role = roleOf(n),
            text = if (n.isPassword) null else text,
            description = desc,
            resourceId = id,
            className = n.className?.toString(),
            bounds = b,
            clickable = n.isClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            enabled = n.isEnabled,
            focused = n.isFocused,
            password = n.isPassword,
            depth = depth,
        )
    }

    private fun roleOf(n: AccessibilityNodeInfo): Role {
        val c = n.className?.toString().orEmpty()
        return when {
            n.isEditable || c.endsWith("EditText") -> Role.TEXT_FIELD
            n.isCheckable && c.contains("Switch") -> Role.SWITCH
            n.isCheckable -> Role.CHECKBOX
            c.endsWith("Button") || c.endsWith("ImageButton") -> Role.BUTTON
            c.contains("RecyclerView") || c.endsWith("ListView") || c.endsWith("ScrollView") -> Role.LIST
            c.endsWith("ImageView") -> if (n.isClickable) Role.BUTTON else Role.IMAGE
            c.endsWith("TextView") -> if (n.isClickable && n.text.isNullOrBlank()) Role.BUTTON else Role.TEXT
            n.isClickable && (n.text != null || n.contentDescription != null) -> Role.BUTTON
            n.text != null -> Role.TEXT
            else -> Role.CONTAINER
        }
    }
}
