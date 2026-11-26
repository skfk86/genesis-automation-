package com.genesis.app.hidden

import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.app.service.NodeMapper
import com.genesis.automation.ui.OpResult
import com.genesis.automation.ui.ScreenSnapshot
import com.genesis.automation.ui.ScrollDirection
import com.genesis.automation.ui.UiBackend
import com.genesis.automation.ui.UiElement
import com.genesis.automation.ui.UiOp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One hidden display created by the host. */
class HiddenSession(
    val host: IHiddenDisplayHost,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val createInfo: JSONObject,
) {
    fun release() {
        runCatching { host.releaseDisplay() }
    }

    companion object {
        /** Creates a hidden display the size of the main screen (capped at 1080 px wide). */
        fun create(context: Context, host: IHiddenDisplayHost): Result<HiddenSession> = runCatching {
            val dm = context.resources.displayMetrics
            var w = minOf(dm.widthPixels, dm.heightPixels)
            var h = maxOf(dm.widthPixels, dm.heightPixels)
            if (w > 1080) { h = h * 1080 / w; w = 1080 }
            val info = JSONObject(host.createDisplay(w, h, dm.densityDpi))
            val id = info.optInt("displayId", -1)
            check(id >= 0) { "display not created: ${info.optString("error")} ${info.optJSONArray("attempts")}" }
            HiddenSession(host, id, w, h, info)
        }
    }
}

/**
 * Reads and drives an app on the hidden display. Reading always goes through the accessibility
 * service (it sees every display on Android 11+, which the tests check). Actions prefer
 * accessibility node actions, then an accessibility gesture on that display, then injected input
 * from the host. Every action records which path worked, for the report.
 */
class HiddenDisplayBackend(
    private val service: GenesisAccessibilityService,
    private val session: HiddenSession,
    /** Start apps as a separate task on the hidden display, leaving any copy on the main screen alone. */
    private val newInstance: Boolean = true,
) : UiBackend {

    override val id = "hidden-display"

    /** How each kind of action was carried out (for example "tap:a11y-click" -> 3). */
    val paths: MutableMap<String, Int> = ConcurrentHashMap()

    override suspend fun snapshot(): ScreenSnapshot? {
        val root = service.rootOnDisplay(session.displayId) ?: return null
        return NodeMapper.snapshot(root, session.width, session.height, System.currentTimeMillis())
    }

    override suspend fun perform(op: UiOp): OpResult = when (op) {
        is UiOp.Tap -> tap(op.target)
        is UiOp.SetText -> setText(op.target, op.text)
        is UiOp.Scroll -> scroll(op.container, op.direction)
        UiOp.Back -> key(KeyEvent.KEYCODE_BACK, "back")
        is UiOp.LaunchApp -> launch(op.packageName)
    }

    private fun root(): AccessibilityNodeInfo? = service.rootOnDisplay(session.displayId)

    private suspend fun tap(target: UiElement): OpResult {
        val node = root()?.let { NodeMapper.findLive(it, target) }
        if (node != null && NodeMapper.clickNodeOrAncestor(node)) return done("tap", "a11y-click")
        val b = target.bounds
        val path = Path().apply { moveTo(b.centerX.toFloat(), b.centerY.toFloat()) }
        if (service.gestureOnDisplay(session.displayId, path, 60)) return done("tap", "a11y-gesture")
        val r = hostCall { session.host.tap(session.displayId, b.centerX, b.centerY) }
        return if (r.optBoolean("ok")) done("tap", "inject") else failed("tap", r.optString("error"))
    }

    private fun setText(target: UiElement, text: String): OpResult {
        val node = root()?.let { NodeMapper.findLive(it, target) } ?: return failed("set_text", "field not found")
        if (!node.isEditable) return failed("set_text", "not editable")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) done("set_text", "a11y-set-text")
        else failed("set_text", "set text refused")
    }

    private suspend fun scroll(container: UiElement?, dir: ScrollDirection): OpResult {
        val action = if (dir == ScrollDirection.FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val r = root()
        val node = container?.let { c -> r?.let { NodeMapper.findLive(it, c) } } ?: r?.let { NodeMapper.firstScrollable(it) }
        if (node != null && node.performAction(action)) return done("scroll", "a11y-scroll")
        val x = session.width / 2f
        val (y1, y2) = if (dir == ScrollDirection.FORWARD) session.height * 0.7f to session.height * 0.3f else session.height * 0.3f to session.height * 0.7f
        if (service.gestureOnDisplay(session.displayId, Path().apply { moveTo(x, y1); lineTo(x, y2) }, 350)) {
            delay(150)
            return done("scroll", "a11y-gesture")
        }
        val res = hostCall { session.host.swipe(session.displayId, x.toInt(), y1.toInt(), x.toInt(), y2.toInt(), 350) }
        return if (res.optBoolean("ok")) done("scroll", "inject") else failed("scroll", res.optString("error"))
    }

    /** Keys need injected input: the accessibility service's global actions act on the main screen. */
    fun key(code: Int, name: String): OpResult {
        val r = hostCall { session.host.key(session.displayId, code) }
        return if (r.optBoolean("ok")) done(name, "inject") else failed(name, r.optString("error"))
    }

    private fun launch(pkg: String): OpResult {
        val cn = service.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return failed("launch", "app $pkg not installed or not visible")
        val r = hostCall { session.host.launch(session.displayId, cn.flattenToShortString(), newInstance) }
        return if (r.optBoolean("ok")) done("launch", r.optString("how")) else failed("launch", r.optString("error", r.optString("output")))
    }

    /** Host calls fail with a remote exception once the host is gone; report that instead of crashing. */
    private inline fun hostCall(call: () -> String): JSONObject = try {
        JSONObject(call())
    } catch (t: Exception) {
        JSONObject().put("ok", false).put("error", "host unavailable: ${t.javaClass.simpleName}")
    }

    private fun done(kind: String, how: String): OpResult {
        paths.merge("$kind:$how", 1, Int::plus)
        return OpResult(true, how)
    }

    private fun failed(kind: String, why: String): OpResult {
        paths.merge("$kind:failed", 1, Int::plus)
        return OpResult(false, why)
    }
}
