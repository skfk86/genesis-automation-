package com.genesis.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.genesis.app.BuildConfig
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.automation.ui.EffectLedger
import com.genesis.automation.ui.EffectRecord
import com.genesis.automation.ui.EffectStore
import com.genesis.automation.ui.EngineClock
import com.genesis.automation.ui.Pacer
import com.genesis.automation.ui.RunReport
import com.genesis.automation.ui.RunResult
import com.genesis.automation.ui.UiRunner
import com.genesis.automation.ui.UiTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

object SystemEngineClock : EngineClock {
    override fun nowMillis() = System.currentTimeMillis()
    override suspend fun sleep(millis: Long) = delay(millis)
}

/** Effect records on disk, so a crash between "tapped send" and "confirmed" can never cause a second send. */
class FileEffectStore(private val file: File) : EffectStore {
    private val serializer = ListSerializer(EffectRecord.serializer())
    private val map: LinkedHashMap<String, EffectRecord> = LinkedHashMap<String, EffectRecord>().apply {
        runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrNull()?.forEach { put(it.key, it) }
    }

    @Synchronized override fun get(key: String) = map[key]

    @Synchronized override fun put(record: EffectRecord) {
        map[record.key] = record
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(serializer, map.values.toList()))
        tmp.renameTo(file)
    }
}

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val genesisVersion: String,
    val appVersions: Map<String, String>,
)

@Serializable
data class ExportedReport(val device: DeviceInfo, val runs: List<RunReport>)

object Engine {

    private var pacer: Pacer? = null
    private var ledger: EffectLedger? = null
    private val runs = mutableListOf<RunReport>()

    val history: List<RunReport> get() = synchronized(runs) { runs.toList() }

    private fun ledger(context: Context): EffectLedger =
        ledger ?: EffectLedger(FileEffectStore(File(context.filesDir, "effects.json")), SystemEngineClock).also { ledger = it }

    private fun pacer(): Pacer = pacer ?: Pacer(SystemEngineClock).also { pacer = it }

    /** Runs a task through the accessibility service, with the stop bar shown for its whole duration. */
    suspend fun run(context: Context, task: UiTask): RunReport {
        val service = GenesisAccessibilityService.instance
        val report = if (service == null) {
            val now = System.currentTimeMillis()
            RunReport(task.id, task.name, "none", now, now, RunResult.FAILED,
                "خدمة إمكانية الوصول غير مفعّلة", emptyList(), emptyList())
        } else {
            service.beginTask()
            try {
                withContext(Dispatchers.Default) {
                    UiRunner(service, SystemEngineClock, service.stopSignal, service.approvalGate, ledger(context), pacer()).run(task)
                }
            } finally {
                service.endTask()
            }
        }
        record(context, report)
        return report
    }

    /** Adds a report produced outside the runner (for example the read-only screen test). */
    fun record(context: Context, report: RunReport) {
        synchronized(runs) { runs += report }
        File(context.filesDir, "reports.jsonl").appendText(report.toJson().replace("\n", "") + "\n")
    }

    fun deviceInfo(context: Context): DeviceInfo {
        val pm = context.packageManager
        val apps = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging")
            .mapNotNull { pkg ->
                runCatching { pkg to (pm.getPackageInfo(pkg, 0).versionName ?: "?") }.getOrNull()
            }.toMap()
        return DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME, apps)
    }

    fun exportJson(context: Context): String =
        Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ExportedReport.serializer(), ExportedReport(deviceInfo(context), history))

    fun isInstalled(context: Context, pkg: String): Boolean =
        try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
}
