package com.genesis.app.hidden

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/**
 * Entry point Shizuku starts in its own process with shell identity (uid 2000). It must keep a
 * public no-argument constructor. Shizuku calls [destroy] when the service is removed.
 */
@Keep
class HiddenDisplayUserService : HostCore(ShellContext.create(), "com.android.shell") {

    override fun destroy() {
        super.destroy()
        exitProcess(0)
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}

/** A context that presents itself as [pkg]. System services check that the package belongs to the caller. */
internal class PackageContext(base: Context, private val pkg: String) : ContextWrapper(base) {
    override fun getPackageName(): String = pkg
    override fun getOpPackageName(): String = pkg
    override fun getApplicationContext(): Context = this

    @RequiresApi(31)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName(pkg).build()
}

/** Builds a system context in a process started by app_process (the Shizuku user service). */
internal object ShellContext {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    fun create(): Context {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        var thread = at.getMethod("currentActivityThread").invoke(null)
        if (thread == null) {
            thread = try {
                at.getMethod("systemMain").invoke(null)
            } catch (_: Throwable) {
                // Same approach as scrcpy: a bare ActivityThread marked as the system thread.
                val ctor = at.getDeclaredConstructor().apply { setAccessible(true) }
                ctor.newInstance().also { t ->
                    at.getDeclaredField("sCurrentActivityThread").apply { setAccessible(true) }.set(null, t)
                    at.getDeclaredField("mSystemThread").apply { setAccessible(true) }.setBoolean(t, true)
                }
            }
        }
        val system = at.getMethod("getSystemContext").invoke(thread) as Context
        return PackageContext(system, HiddenDisplayUserService.SHELL_PACKAGE)
    }
}
