package com.genesis.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genesis.app.engine.Engine
import com.genesis.app.hidden.HiddenDisplayManager
import com.genesis.app.hidden.HiddenDisplayManager.State
import com.genesis.app.hidden.HiddenDisplaySuite
import com.genesis.app.hidden.HiddenSoak
import com.genesis.app.hidden.HiddenSuiteReport
import com.genesis.app.hidden.ShizukuLossDriver
import com.genesis.app.service.GenesisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs outlive the screen: the person is expected to leave GENESIS and use the phone meanwhile. */
private val hiddenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val CardBg = Color(0xFF1E1840)
private val Soft = Color(0xFFB8B5CC)

@Composable
fun HiddenDisplaySection() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(State.SHIZUKU_NOT_RUNNING) }
    var serviceOn by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var lastReport by remember { mutableStateOf(HiddenSuiteReport.latestJson(context)) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var summary by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        HiddenDisplayManager.init(context)
        while (true) {
            state = HiddenDisplayManager.refresh()
            serviceOn = GenesisAccessibilityService.instance != null
            delay(1000)
        }
    }

    fun share(json: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "GENESIS hidden display report")
            .putExtra(Intent.EXTRA_TEXT, json)
        context.startActivity(Intent.createChooser(send, "مشاركة تقرير الشاشة المخفية").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(
        Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("المرحلة 3: الشاشة المخفية (تجريبي)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("يعمل GENESIS على شاشة افتراضية لا تراها، وتبقى شاشتك كما هي. لا تُرسل أي رسالة حقيقية: الإرسال فقط داخل تطبيق المحادثة التجريبي، وواتساب يُفتح ويُقرأ فقط.", color = Soft, fontSize = 13.sp)

        Text("خدمة إمكانية الوصول: " + if (serviceOn) "✅ مفعّلة" else "⛔ غير مفعّلة (فعّلها من القسم 1)", color = Color.White, fontSize = 13.sp)
        Text("تطبيق المحادثة التجريبي: " + if (Engine.isInstalled(context, HiddenDisplaySuite.FIXTURE)) "✅ مثبّت" else "⛔ غير مثبّت (genesis-test-chat.apk)", color = Color.White, fontSize = 13.sp)
        Text("Shizuku: " + when (state) {
            State.SHIZUKU_NOT_INSTALLED -> "⛔ غير مثبّت. ثبّته ثم شغّله (راجع الدليل)."
            State.SHIZUKU_NOT_RUNNING -> "⛔ مثبّت لكنه لا يعمل. افتح Shizuku واضغط «بدء» عبر تصحيح الأخطاء اللاسلكي."
            State.SHIZUKU_TOO_OLD -> "⛔ إصدار قديم جداً. حدّث Shizuku."
            State.NO_PERMISSION -> "🔑 يعمل، لكن GENESIS يحتاج إذنك."
            State.CONNECTING -> "⏳ يتصل…"
            State.CONNECTED -> "✅ متصل (${HiddenDisplayManager.mode})"
        }, color = Color.White, fontSize = 13.sp)

        if (state == State.NO_PERMISSION) {
            Button(onClick = { HiddenDisplayManager.requestPermission() }) { Text("امنح GENESIS إذن Shizuku") }
        }

        val ready = serviceOn && state == State.CONNECTED && !running
        Button(enabled = ready, onClick = {
            val service = GenesisAccessibilityService.instance ?: return@Button
            running = true
            summary = ""
            hiddenScope.launch {
                val r = HiddenDisplaySuite(context.applicationContext, service, ShizukuLossDriver()) { progress = it }.run()
                lastReport = r.toJson()
                summary = r.checks.joinToString("\n") { "${mark(it.status)} ${it.id} ${it.title}" }
                progress = "انتهى الاختبار التلقائي"
                running = false
            }
        }) { Text("ابدأ الاختبار التلقائي (حوالي دقيقتين)") }
        Text("اترك الهاتف أثناء الاختبار التلقائي. سيفتح الإعدادات مرة على شاشتك الرئيسية عمداً لاختبار الاستخدام المتزامن ثم يعود.", color = Soft, fontSize = 12.sp)

        Text("الاختبار اليدوي (60 ثانية): اضغط ثم اخرج من GENESIS واستخدم هاتفك طبيعياً. جرّب أثناءه: زر «إيقاف» العائم أو خفض الصوت، أو أوقف Shizuku من تطبيقه ثم شغّله مجدداً.", color = Soft, fontSize = 12.sp)
        for ((label, target) in listOf("يدوي: تطبيق المحادثة التجريبي" to HiddenSoak.SOAK_FIXTURE, "يدوي: واتساب (قراءة وتمرير فقط)" to HiddenSoak.SOAK_WHATSAPP)) {
            OutlinedButton(enabled = ready, onClick = {
                val service = GenesisAccessibilityService.instance ?: return@OutlinedButton
                running = true
                summary = ""
                hiddenScope.launch {
                    val r = HiddenSoak(context.applicationContext, service, target, progress = { progress = it },
                        preview = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { preview = it.asImageBitmap() } }).run()
                    lastReport = r.toJson()
                    summary = r.checks.joinToString("\n") { "${mark(it.status)} ${it.title}\n${it.detail}" }
                    progress = "انتهى الاختبار اليدوي"
                    running = false
                }
            }) { Text(label, color = Color.White) }
        }

        if (progress.isNotBlank()) Text(progress, color = Color.White, fontSize = 13.sp)
        if (summary.isNotBlank()) Text(summary, color = Color.White, fontSize = 13.sp)
        preview?.let {
            Text("صورة مصغّرة لما على الشاشة المخفية (تبقى على هاتفك):", color = Soft, fontSize = 12.sp)
            Image(it, contentDescription = "الشاشة المخفية", modifier = Modifier.height(220.dp))
        }
        lastReport?.let { json ->
            Button(onClick = { share(json) }) { Text("مشاركة تقرير الشاشة المخفية") }
        }
    }
}

private fun mark(status: String) = when (status) {
    "PASS" -> "✅"
    "FAIL" -> "❌"
    "SKIPPED" -> "⏭"
    else -> "✋"
}
