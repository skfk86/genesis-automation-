package com.genesis.app.hidden

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The privileged half of the hidden display. It creates a virtual display that is never shown
 * on the phone's screen (its frames go to an [ImageReader] owned by this process), starts apps
 * on it, and injects input into it only.
 *
 * On the phone it runs inside the Shizuku user service with shell identity
 * ([HiddenDisplayUserService]). In emulator tests it runs inside the test process with the
 * shell's permissions adopted. [packageName] must match the calling uid ("com.android.shell" for
 * the shell), because the display service checks it.
 *
 * Nothing here reads app content: reading is done by the accessibility service in the app.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "WrongConstant")
open class HostCore(private val context: Context, private val packageName: String) : IHiddenDisplayHost.Stub() {

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    @Volatile private var frames = 0L
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastError: String? = null
    private var width = 0
    private var height = 0

    override fun destroy() {
        releaseDisplay()
    }

    @Synchronized
    override fun createDisplay(width: Int, height: Int, dpi: Int): String {
        releaseDisplay()
        this.width = width
        this.height = height
        val out = JSONObject()
        val attempts = JSONArray()
        val thread = HandlerThread("genesis-hidden-frames").also { it.start() }
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, Handler(thread.looper))
        val dm = displayManager()
        for ((name, flags) in flagLadder()) {
            try {
                val vd = dm.createVirtualDisplay("genesis-hidden", width, height, dpi, r.surface, flags)
                if (vd != null) {
                    display = vd
                    reader = r
                    frameThread = thread
                    attempts.put(JSONObject().put("flagSet", name).put("ok", true))
                    out.put("displayId", vd.display.displayId)
                    out.put("flagSet", name)
                    out.put("flags", flags)
                    out.put("imePolicy", hideImeOn(vd.display.displayId))
                    break
                }
                attempts.put(JSONObject().put("flagSet", name).put("ok", false).put("error", "null display"))
            } catch (t: Throwable) {
                attempts.put(JSONObject().put("flagSet", name).put("ok", false).put("error", t.short()))
            }
        }
        out.put("attempts", attempts)
        out.put("uid", Process.myUid())
        if (display == null) {
            r.close()
            thread.quitSafely()
            out.put("error", "no flag set was accepted")
            lastError = "createDisplay failed"
        }
        return out.toString()
    }

    override fun launch(displayId: Int, component: String, newInstance: Boolean): String {
        val out = JSONObject()
        val cn = ComponentName.unflattenFromString(component)
            ?: return out.put("ok", false).put("error", "bad component $component").toString()
        if (Process.myUid() == SHELL_UID || Process.myUid() == ROOT_UID) {
            // The shell cannot use Context.startActivity here (no app thread), so use the am command.
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (newInstance) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0)
            val res = exec(listOf("am", "start", "--display", displayId.toString(), "-f", flags.toString(), "-n", cn.flattenToShortString()))
            val ok = res.first == 0 && !res.second.contains("Error", ignoreCase = true) && !res.second.contains("Exception")
            return out.put("ok", ok).put("how", "am start --display").put("output", res.second.take(300)).toString()
        }
        return try {
            val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (newInstance) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0))
            context.startActivity(intent, options.toBundle())
            out.put("ok", true).put("how", "ActivityOptions.setLaunchDisplayId").toString()
        } catch (t: Throwable) {
            if (t is ActivityNotFoundException || t is SecurityException) lastError = t.short()
            out.put("ok", false).put("how", "ActivityOptions.setLaunchDisplayId").put("error", t.short()).toString()
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int): String = injectSafely {
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), displayId))
        SystemClock.sleep(40)
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), displayId))
    }

    override fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String = injectSafely {
        val steps = 12
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), displayId))
        for (i in 1..steps) {
            SystemClock.sleep((durationMs / steps).coerceAtLeast(5))
            val f = i / steps.toFloat()
            inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, displayId))
        }
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), displayId))
    }

    override fun key(displayId: Int, keyCode: Int): String = injectSafely {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val e = KeyEvent(now, SystemClock.uptimeMillis(), action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            setDisplayId(e, displayId)
            inject(e)
        }
    }

    @Synchronized
    override fun releaseDisplay() {
        display?.let { runCatching { it.release() } }
        display = null
        reader?.let { runCatching { it.close() } }
        reader = null
        frameThread?.quitSafely()
        frameThread = null
        lastFrame = null
    }

    override fun stats(): String {
        val o = JSONObject()
        o.put("uid", Process.myUid())
        o.put("pid", Process.myPid())
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("frames", frames)
        o.put("displayId", display?.display?.displayId ?: -1)
        o.put("lastError", lastError ?: JSONObject.NULL)
        runCatching {
            val status = File("/proc/self/status").readLines()
            status.firstOrNull { it.startsWith("VmRSS:") }?.let { o.put("rssKb", it.filter(Char::isDigit).toLong()) }
        }
        runCatching {
            // utime and stime are fields 14 and 15 of /proc/self/stat, in clock ticks (100 per second on Android).
            val stat = File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(' ')
            o.put("cpuMs", (fields[11].toLong() + fields[12].toLong()) * 10)
        }
        return o.toString()
    }

    override fun thumbnail(maxWidth: Int): ByteArray {
        val bmp = lastFrame ?: return ByteArray(0)
        val scale = (maxWidth.toFloat() / bmp.width).coerceAtMost(1f)
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)
        return bos.toByteArray()
    }

    // ---- internals ----

    private fun onFrame(r: ImageReader) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            frames++
            val now = SystemClock.uptimeMillis()
            // Keep one copy per second for the in-app preview; frames are otherwise discarded at once.
            if (now - lastFrameAt >= 1000) {
                lastFrameAt = now
                val plane = img.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val full = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                lastFrame = if (rowPixels == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            }
        } catch (t: Throwable) {
            lastError = "frame: ${t.short()}"
        } finally {
            img.close()
        }
    }

    private fun displayManager(): DisplayManager {
        // The hidden constructor takes our context, so the display is created in the name of
        // [packageName] (which must belong to the calling uid). Fall back to the normal service.
        return try {
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.setAccessible(true)
            ctor.newInstance(PackageContext(context, packageName))
        } catch (t: Throwable) {
            Log.w(TAG, "DisplayManager(Context) unavailable: ${t.short()}")
            context.getSystemService(DisplayManager::class.java)
        }
    }

    /** Flag sets tried in order. The first is what a fully hidden, independent display needs. */
    private fun flagLadder(): List<Pair<String, Int>> {
        val base = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val list = mutableListOf<Pair<String, Int>>()
        if (Build.VERSION.SDK_INT >= 34) {
            list += "trusted+own-focus+always-unlocked" to (base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or
                FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP)
            list += "trusted+own-focus" to (base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += "trusted" to (base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_TOUCH_FEEDBACK_DISABLED)
        }
        list += "public" to base
        list += "private" to (FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL)
        return list
    }

    /** Asks the window manager never to show a keyboard for this display, so none appears on the main screen. */
    private fun hideImeOn(displayId: Int): String {
        if (Build.VERSION.SDK_INT < 31) return "not available before Android 12"
        return try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val iwm = wmg.getMethod("getWindowManagerService").invoke(null)!!
            iwm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(iwm, displayId, DISPLAY_IME_POLICY_HIDE)
            "hide"
        } catch (t: Throwable) {
            "failed: ${t.short()}"
        }
    }

    private fun injectSafely(block: () -> Unit): String = try {
        block()
        JSONObject().put("ok", true).toString()
    } catch (t: Throwable) {
        lastError = "inject: ${t.short()}"
        JSONObject().put("ok", false).put("error", t.short()).toString()
    }

    private fun motion(down: Long, at: Long, action: Int, x: Float, y: Float, displayId: Int): MotionEvent =
        MotionEvent.obtain(down, at, action, x, y, 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayId(it, displayId)
        }

    private fun setDisplayId(e: InputEvent, displayId: Int) {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(e, displayId)
    }

    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        val cls = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (_: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        cls.getMethod("getInstance").invoke(null)!! to
            cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    @SuppressLint("Recycle")
    private fun inject(e: InputEvent) {
        val ok = injector.second.invoke(injector.first, e, INJECT_MODE_ASYNC) as Boolean
        if (!ok) throw IllegalStateException("injectInputEvent returned false")
    }

    private fun exec(cmd: List<String>): Pair<Int, String> = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = p.waitFor(8, TimeUnit.SECONDS)
        val text = p.inputStream.bufferedReader().readText()
        if (!finished) p.destroy()
        (if (finished) p.exitValue() else -1) to text
    } catch (t: Throwable) {
        -1 to t.short()
    }

    companion object {
        private const val TAG = "GenesisHidden"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        private const val INJECT_MODE_ASYNC = 0
        private const val DISPLAY_IME_POLICY_HIDE = 2

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden constants with stable values).
        const val FLAG_PUBLIC = 1
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

        fun Throwable.short(): String {
            val root = generateSequence(this) { it.cause }.last()
            return "${root.javaClass.simpleName}: ${root.message?.take(200)}"
        }
    }
}
