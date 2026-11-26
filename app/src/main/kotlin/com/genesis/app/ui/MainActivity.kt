package com.genesis.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genesis.app.ai.ReplyDrafter
import com.genesis.app.engine.ChatTarget
import com.genesis.app.engine.Engine
import com.genesis.app.engine.PrototypeTasks
import com.genesis.app.service.GenesisAccessibilityService
import com.genesis.automation.ui.ChatMessage
import com.genesis.automation.ui.ChatScreenReader
import com.genesis.automation.ui.Direction
import com.genesis.automation.ui.RunReport
import com.genesis.automation.ui.RunResult
import com.genesis.automation.ui.ScreenSnapshot
import com.genesis.automation.ui.StepLog
import com.genesis.automation.ui.StepOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B0B12)
private val Card = Color(0xFF16162A)
private val Accent = Color(0xFF7C5CFF)
private val Muted = Color(0xFFB8B5CC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { PrototypeScreen() }
            }
        }
    }
}

@Composable
private fun PrototypeScreen() {
    val context = LocalContext.current
    val scope = rememberScrollState()
    val co = rememberCoroutineScope()
    var serviceOn by remember { mutableStateOf(GenesisAccessibilityService.instance != null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(PrototypeTasks.CHAT_TARGETS.first()) }
    var contact by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var composeId by remember { mutableStateOf(0L) }
    var history by remember { mutableStateOf(Engine.history) }

    LaunchedEffect(Unit) {
        while (true) { serviceOn = GenesisAccessibilityService.instance != null; delay(1000) }
    }

    fun bringBack() {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        (GenesisAccessibilityService.instance ?: context).startActivity(i)
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(scope).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("GENESIS · النموذج التجريبي", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("إثبات إمكانية تقنية فقط، وليس للاستخدام التجاري. كل إرسال يطلب موافقتك. للإيقاف: زر «إيقاف» العائم، أو مفتاح خفض الصوت، أو لمس الشاشة.", color = Muted, fontSize = 13.sp)

        HiddenDisplaySection()

        Section("1. خدمة إمكانية الوصول") {
            Text(if (serviceOn) "✅ مفعّلة" else "⛔ غير مفعّلة", color = Color.White)
            if (!serviceOn) {
                Text("افتح الإعدادات ← إمكانية الوصول ← GENESIS ← فعّل. إن ظهرت رسالة «إعداد مقيّد» فافتح معلومات تطبيق GENESIS ← القائمة (⋮) ← «السماح بالإعدادات المقيّدة» ثم أعد المحاولة.", color = Muted, fontSize = 13.sp)
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("افتح إعدادات إمكانية الوصول") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("افتح معلومات التطبيق") }
            }
        }

        Section("2. ت-1: قراءة أي شاشة") {
            Text("اضغط ثم انتقل خلال 5 ثوانٍ إلى أي تطبيق. سيقرأ GENESIS عناصر الشاشة ويعود.", color = Muted, fontSize = 13.sp)
            Button(enabled = serviceOn && !busy, onClick = {
                co.launch {
                    busy = true
                    for (s in 5 downTo 1) { status = "القراءة بعد $s…"; delay(1000) }
                    val snap = GenesisAccessibilityService.instance?.snapshot()
                    val report = readReport(snap)
                    Engine.record(context, report)
                    history = Engine.history
                    status = report.summary
                    bringBack()
                    busy = false
                }
            }) { Text("ابدأ ت-1") }
        }

        Section("3. ت-2: الإعدادات ← حول الهاتف") {
            Text("يفتح الإعدادات، يمرّر حتى «حول الهاتف»، يدخل، ويتحقق من ظهور معلومات الجهاز. لا يغيّر أي إعداد.", color = Muted, fontSize = 13.sp)
            Button(enabled = serviceOn && !busy, onClick = {
                co.launch {
                    busy = true; status = "ت-2 يعمل…"
                    val r = Engine.run(context, PrototypeTasks.settingsAbout())
                    history = Engine.history; status = describe(r); bringBack(); busy = false
                }
            }) { Text("ابدأ ت-2") }
        }

        Section("4. ت-7 إلى ت-9: محادثة") {
            Text("افتح أولاً محادثة الاختبار في التطبيق المختار واتركها مفتوحة، ثم عد إلى هنا.", color = Muted, fontSize = 13.sp)
            PrototypeTasks.CHAT_TARGETS.filter { Engine.isInstalled(context, it.packageName) }.forEach { t ->
                val selected = t == target
                OutlinedButton(onClick = { target = t; messages = emptyList(); reply = "" },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Accent.copy(alpha = 0.25f) else Color.Transparent)) {
                    Text((if (selected) "● " else "○ ") + t.label, color = Color.White)
                }
            }
            OutlinedTextField(contact, { contact = it }, label = { Text("اسم جهة الاتصال (للعرض فقط)") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = serviceOn && !busy, onClick = {
                co.launch {
                    busy = true; status = "يفتح ${target.label} ويقرأ المحادثة…"
                    val r = Engine.run(context, PrototypeTasks.openChat(target))
                    val snap = GenesisAccessibilityService.instance?.snapshot()
                    if (r.result == RunResult.SUCCESS && snap != null && snap.packageName == target.packageName) {
                        val chat = ChatScreenReader(target.profile).read(snap)
                        messages = chat.messages
                        Engine.record(context, chatReadReport(target, chat.messages, chat.rtl, snap))
                        status = "قرأ ${chat.messages.size} رسالة (واردة ${chat.messages.count { it.direction == Direction.INCOMING }}، صادرة ${chat.messages.count { it.direction == Direction.OUTGOING }})"
                    } else status = describe(r)
                    history = Engine.history; bringBack(); busy = false
                }
            }) { Text("اقرأ المحادثة") }

            if (messages.isNotEmpty()) {
                Text("ما قرأه GENESIS (يبقى على هاتفك، لا يدخل في التقرير):", color = Muted, fontSize = 12.sp)
                messages.takeLast(8).forEach { m ->
                    val who = when (m.direction) { Direction.INCOMING -> "⬅ وارد"; Direction.OUTGOING -> "➡ صادر"; Direction.UNKNOWN -> "؟" }
                    Text("$who: ${m.text}", color = Color.White, fontSize = 13.sp)
                }
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("مفتاح Claude API (اختياري، لا يُحفظ)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedButton(enabled = apiKey.isNotBlank() && !busy, onClick = {
                    co.launch {
                        busy = true; status = "يكتب Claude مسودة…"
                        ReplyDrafter.draft(apiKey.trim(), messages, contact.ifBlank { "جهة الاتصال" })
                            .onSuccess { reply = it; status = "المسودة جاهزة، راجعها وعدّلها" }
                            .onFailure { status = "تعذّر: ${it.message}" }
                        busy = false
                    }
                }) { Text("اقترح رداً بالذكاء الاصطناعي") }
                OutlinedTextField(reply, { reply = it; composeId = 0L }, label = { Text("نص الرد") }, modifier = Modifier.fillMaxWidth().height(120.dp))
                Button(enabled = serviceOn && !busy && reply.isNotBlank(), onClick = {
                    co.launch {
                        busy = true
                        if (composeId == 0L) composeId = System.currentTimeMillis()
                        status = "يرسل عبر المحرك. ستظهر لك نافذة الموافقة داخل ${target.label}."
                        val key = "chat:${target.packageName}:$composeId"
                        val r = Engine.run(context, PrototypeTasks.sendReply(target, contact.ifBlank { "جهة الاتصال" }, reply.trim(), key))
                        history = Engine.history; status = describe(r); bringBack(); busy = false
                    }
                }) { Text("أرسل عبر المحرك (مع موافقة)") }
                Text("الضغط مرة ثانية على نفس الرد لا يرسله مرة ثانية؛ المحرك يتذكر أنه أُرسل.", color = Muted, fontSize = 12.sp)
            }
        }

        if (status.isNotBlank()) Section("الحالة") { Text(status, color = Color.White) }

        Section("5. التقرير") {
            Text("${history.size} تشغيل مسجّل. التقرير لا يحتوي نصوص الرسائل، فقط النتائج والأطوال والخطوات ومعلومات الجهاز.", color = Muted, fontSize = 13.sp)
            history.takeLast(6).reversed().forEach { r -> Text("• ${r.taskName}: ${r.result}", color = Color.White, fontSize = 13.sp) }
            Button(enabled = history.isNotEmpty(), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "GENESIS prototype report")
                    .putExtra(Intent.EXTRA_TEXT, Engine.exportJson(context))
                context.startActivity(Intent.createChooser(send, "مشاركة التقرير").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("مشاركة التقرير") }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        content()
    }
}

private fun describe(r: RunReport): String {
    val head = when (r.result) {
        RunResult.SUCCESS -> "✅ نجح"
        RunResult.FAILED -> "❌ فشل"
        RunResult.STOPPED -> "⏹ أوقف"
        RunResult.NEEDS_USER -> "✋ يحتاجك"
        RunResult.REJECTED_BY_USER -> "🚫 رفضت الإرسال"
    }
    return "$head: ${r.taskName}\n${r.summary}"
}

private fun readReport(snap: ScreenSnapshot?): RunReport {
    val now = System.currentTimeMillis()
    if (snap == null) return RunReport("t1-$now", "T-1 read any screen", "accessibility", now, now, RunResult.FAILED, "screen not readable", emptyList(), emptyList())
    val roles = snap.elements.groupingBy { it.role }.eachCount()
    val withIds = snap.elements.count { it.resourceId != null }
    val chat = ChatScreenReader(com.genesis.automation.ui.ChatProfile.forPackage(snap.packageName)).read(snap)
    val summary = "app=${snap.packageName}, elements=${snap.elements.size}, withIds=$withIds, roles=$roles, " +
        "chatLike=${chat.inputFound}, messages=${chat.messages.size}"
    return RunReport("t1-$now", "T-1 read any screen", "accessibility", now, now, RunResult.SUCCESS, summary,
        listOf(StepLog(0, "Read", "read screen", StepOutcome.OK, summary, 0)), emptyList())
}

private fun chatReadReport(t: ChatTarget, msgs: List<ChatMessage>, rtl: Boolean, snap: ScreenSnapshot): RunReport {
    val now = System.currentTimeMillis()
    val shape = msgs.joinToString(" ") { "${it.direction.name.first()}${it.text.length}" }
    val summary = "app=${t.packageName}, profile=${t.profile.name}, rtl=$rtl, messages=${msgs.size}, " +
        "incoming=${msgs.count { it.direction == Direction.INCOMING }}, outgoing=${msgs.count { it.direction == Direction.OUTGOING }}, " +
        "unknown=${msgs.count { it.direction == Direction.UNKNOWN }}, elements=${snap.elements.size}, shape=[$shape]"
    return RunReport("t7-read-$now", "T-7 chat reading (${t.label})", "accessibility", now, now, RunResult.SUCCESS, summary,
        listOf(StepLog(0, "ReadChat", "read conversation", StepOutcome.OK, summary, 0)), emptyList())
}
