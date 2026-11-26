package com.genesis.app

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.genesis.app.hidden.HiddenDisplayManager
import com.genesis.app.hidden.HiddenDisplaySuite
import com.genesis.app.hidden.HostCore
import com.genesis.app.hidden.HostLossDriver
import com.genesis.app.hidden.IHiddenDisplayHost
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.app.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the hidden-display proof on an emulator. There is no Shizuku here, so the privileged
 * host runs inside the test process with the shell's permissions adopted (the same permissions
 * Shizuku gives it on a phone). Everything else is the real code path: the accessibility service
 * reads the hidden display, the engine acts on it, and the main screen is watched throughout.
 * The run script sets hidden_api_policy=1 before the process starts, so the host's reflection works
 * as it does in the Shizuku process.
 */
@RunWith(AndroidJUnit4::class)
class HiddenDisplayOnDeviceTest {

    companion object {
        private const val TAG = "GenesisHiddenE2E"
        private lateinit var ua: UiAutomation

        @BeforeClass @JvmStatic
        fun setUp() {
            ua = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val component = "com.genesis.app/com.genesis.app.service.GenesisAccessibilityService"
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            runBlocking {
                repeat(60) {
                    if (GenesisAccessibilityService.instance != null) return@runBlocking
                    delay(500)
                }
            }
            check(GenesisAccessibilityService.instance != null) { "accessibility service did not start" }
            ua.adoptShellPermissionIdentity()
        }

        @AfterClass @JvmStatic
        fun tearDown() {
            ua.dropShellPermissionIdentity()
            HiddenDisplayManager.useLocalHost(null)
        }

        private fun shell(cmd: String): String =
            ua.executeShellCommand(cmd).use { pfd -> String(ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()) }
    }

    @Test
    fun hiddenDisplayRequirements() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(TAG, "hidden_api_policy=${shell("settings get global hidden_api_policy").trim()}")
        var core = HostCore(ctx, ctx.packageName)
        HiddenDisplayManager.useLocalHost(core)
        val driver = object : HostLossDriver {
            override val description = "simulated inside the test process (no Shizuku on the emulator)"
            override suspend fun loseHost() {
                core.releaseDisplay()
                HiddenDisplayManager.simulateLoss("simulated host loss")
            }
            override suspend fun restoreHost(): IHiddenDisplayHost {
                core = HostCore(ctx, ctx.packageName)
                HiddenDisplayManager.useLocalHost(core)
                return core
            }
        }

        // Put GENESIS on the main screen: it must stay there while the hidden display works.
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val service = GenesisAccessibilityService.instance!!
        runBlocking {
            repeat(40) {
                if (service.mainDisplayState().topPackage == ctx.packageName) return@repeat
                delay(250)
            }
        }

        val report = runBlocking { HiddenDisplaySuite(ctx, service, driver) { Log.i(TAG, "progress: $it") }.run() }
        report.toJson().lines().chunked(40).forEach { Log.i(TAG, it.joinToString("\n")) }
        for (c in report.checks) Log.i(TAG, "${c.status} ${c.id} ${c.title}: ${c.detail} ${c.metrics}")

        val byId = report.checks.associateBy { it.id }
        val required = listOf("H-1", "H-2", "H-5", "H-6", "H-7", "H-8", "H-9", "H-10")
        val failures = required.filter { byId[it]?.status != "PASS" }.map { "$it=${byId[it]?.status}: ${byId[it]?.detail}" }
        assertTrue("hidden display checks failed:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertEquals("WhatsApp is not on the emulator", "SKIPPED", byId["H-3"]?.status)
        assertTrue("no unexpected error", byId["ERR"] == null)
    }
}
