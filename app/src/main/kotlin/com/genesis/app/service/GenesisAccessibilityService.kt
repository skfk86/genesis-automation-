package com.genesis.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.genesis.automation.ui.ApprovalDecision
import com.genesis.automation.ui.ApprovalGate
import com.genesis.automation.ui.ApprovalRequest
import com.genesis.automation.ui.MutableStopSignal
import com.genesis.automation.ui.OpResult
import com.genesis.automation.ui.ScreenSnapshot
import com.genesis.automation.ui.ScrollDirection
import com.genesis.automation.ui.UiBackend
import com.genesis.automation.ui.UiElement
import com.genesis.automation.ui.UiOp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The primary UI backend: reads the accessibility tree and acts through node actions, with
 * dispatchGesture as a fallback. It does nothing on its own; it only executes operations the
 * engine sends while a task the user started is active.
 */
class GenesisAccessibilityService : AccessibilityService(), UiBackend {

    override val id = "accessibility"

    val stopSignal = MutableStopSignal()

    @Volatile var taskActive = false
        private set

    /** While our own gesture plays, touches are ours, not the user's. */
    @Volatile private var ownGestureUntil = 0L
    @Volatile private var lastOwnActionAt = 0L

    private lateinit var overlays: Overlays

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlays = Overlays(this)
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::overlays.isInitialized) overlays.hideAll()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopSignal.stop("interrupted by the system")
    }

    /** Volume-down is a physical stop key while a task runs. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (taskActive && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            stopSignal.stop("volume-down key")
            return true
        }
        return false
    }

    // ---- task lifecycle ----

    suspend fun beginTask() = withContext(Dispatchers.Main) {
        stopSignal.reset()
        taskActive = true
        overlays.showStopBar(
            onStop = { stopSignal.stop("stop button") },
            onOutsideTouch = {
                val now = SystemClock.uptimeMillis()
                val ignored = !taskActive || overlays.approvalVisible || now < ownGestureUntil
                Log.i(TAG, "outside touch: ignored=$ignored sinceOwnAction=${now - lastOwnActionAt}ms")
                if (!ignored) stopSignal.stop("user touched the screen")
            },
        )
    }

    suspend fun endTask() = withContext(Dispatchers.Main) {
        taskActive = false
        overlays.hideAll()
    }

    /** Approval shown on top of the target app. Cancelled (= rejected) if the task is stopped. */
    val approvalGate = ApprovalGate { r: ApprovalRequest ->
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { overlays.showApproval(r.title, r.app, r.content) { answer.complete(it) } }
        var result: Boolean? = null
        while (result == null) {
            if (stopSignal.stopRequested) break
            result = withTimeoutOrNull(300) { answer.await() }
        }
        withContext(Dispatchers.Main) { overlays.hideApproval() }
        if (result == true) ApprovalDecision.Approved else ApprovalDecision.Rejected
    }

    // ---- UiBackend ----

    override suspend fun snapshot(): ScreenSnapshot? {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return null
        val root = rootInActiveWindow ?: return null
        val dm = resources.displayMetrics
        return NodeMapper.snapshot(root, dm.widthPixels, dm.heightPixels, System.currentTimeMillis())
    }

    override suspend fun perform(op: UiOp): OpResult {
        // Our own actions can make the system emit touch-like events (keyboard, focus, clicks);
        // touches shortly after them are not treated as the user taking over.
        lastOwnActionAt = SystemClock.uptimeMillis()
        ownGestureUntil = maxOf(ownGestureUntil, lastOwnActionAt + OWN_ACTION_GRACE_MS)
        val r = performOp(op)
        ownGestureUntil = maxOf(ownGestureUntil, SystemClock.uptimeMillis() + OWN_ACTION_GRACE_MS)
        return r
    }

    private suspend fun performOp(op: UiOp): OpResult = when (op) {
        is UiOp.Tap -> tap(op.target)
        is UiOp.SetText -> setText(op.target, op.text)
        is UiOp.Scroll -> scroll(op.container, op.direction)
        UiOp.Back -> OpResult(performGlobalAction(GLOBAL_ACTION_BACK), "global back")
        is UiOp.LaunchApp -> launch(op.packageName)
    }

    private suspend fun tap(target: UiElement): OpResult {
        val node = findLive(target) ?: return OpResult(false, "element no longer on screen")
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return OpResult(true, "click action")
        val b = target.bounds
        return OpResult(gestureTap(b.centerX.toFloat(), b.centerY.toFloat()), "gesture tap")
    }

    private fun setText(target: UiElement, text: String): OpResult {
        val node = findLive(target) ?: return OpResult(false, "field no longer on screen")
        if (!node.isEditable) return OpResult(false, "not an editable field")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return OpResult(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "set text action")
    }

    private suspend fun scroll(container: UiElement?, dir: ScrollDirection): OpResult {
        val action = if (dir == ScrollDirection.FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val node = container?.let { findLive(it) } ?: firstScrollable()
        if (node != null && node.performAction(action)) return OpResult(true, "scroll action")
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (dir == ScrollDirection.FORWARD) dm.heightPixels * 0.7f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.7f
        return OpResult(gestureSwipe(x, y1, x, y2, 350), "swipe gesture")
    }

    private fun launch(pkg: String): OpResult {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return OpResult(false, "app $pkg not installed or not visible")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); OpResult(true, "launched") }
            .getOrElse { OpResult(false, it.message ?: "launch failed") }
    }

    /** Finds the live node for an element from an earlier snapshot: same id, class and bounds, else the smallest node at its centre. */
    private fun findLive(target: UiElement): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var exact: AccessibilityNodeInfo? = null
        var sameBounds: AccessibilityNodeInfo? = null
        var atCenter: AccessibilityNodeInfo? = null
        var atCenterArea = Long.MAX_VALUE
        NodeMapper.walk(root, 0) { node, _ ->
            val b = NodeMapper.boundsOf(node)
            if (b == target.bounds) {
                if (node.viewIdResourceName == target.resourceId && node.className?.toString() == target.className) exact = exact ?: node
                sameBounds = sameBounds ?: node
            }
            if (b.left <= target.bounds.centerX && target.bounds.centerX <= b.right && b.top <= target.bounds.centerY && target.bounds.centerY <= b.bottom) {
                val area = b.width.toLong() * b.height
                if (area in 1 until atCenterArea && (target.editable == node.isEditable)) { atCenter = node; atCenterArea = area }
            }
        }
        return exact ?: sameBounds ?: atCenter
    }

    private fun firstScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        NodeMapper.walk(root, 0) { n, _ ->
            if (n.isScrollable) {
                val b = NodeMapper.boundsOf(n)
                val a = b.width.toLong() * b.height
                if (a > bestArea) { best = n; bestArea = a }
            }
        }
        return best
    }

    private suspend fun gestureTap(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 60)

    private suspend fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        ownGestureUntil = maxOf(ownGestureUntil, SystemClock.uptimeMillis() + durationMs + OWN_ACTION_GRACE_MS)
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
                val sent = dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!sent && cont.isActive) cont.resume(false)
            }
        }
        delay(100)
        return ok
    }

    companion object {
        private const val TAG = "GenesisE2E"
        private const val OWN_ACTION_GRACE_MS = 1500L

        @Volatile var instance: GenesisAccessibilityService? = null
            private set
    }
}
