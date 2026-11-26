package com.genesis.app.engine

import com.genesis.automation.ui.ChatProfile
import com.genesis.automation.ui.ChatRecipes
import com.genesis.automation.ui.Selector
import com.genesis.automation.ui.Step
import com.genesis.automation.ui.UiTask
import com.genesis.automation.ui.Verification

/** Chat apps the prototype can be pointed at. Unknown apps use the generic profile. */
data class ChatTarget(val label: String, val packageName: String) {
    val profile: ChatProfile get() = ChatProfile.forPackage(packageName)
}

object PrototypeTasks {

    val CHAT_TARGETS = listOf(
        ChatTarget("واتساب", "com.whatsapp"),
        ChatTarget("واتساب للأعمال", "com.whatsapp.w4b"),
        ChatTarget("تيليجرام", "org.telegram.messenger"),
        ChatTarget("رسائل Google", "com.google.android.apps.messaging"),
        ChatTarget("تطبيق المحادثة التجريبي", "com.genesis.fixture.chat"),
    )

    private val ABOUT_LABELS = listOf(
        "About phone", "About emulated device", "About device", "About tablet",
        "حول الهاتف", "عن الهاتف", "حول الجهاز", "معلومات الهاتف", "حول الجوال",
    )
    private val ABOUT_PROOF = listOf(
        "Android version", "Build number", "Model", "Device name", "Software information",
        "إصدار Android", "إصدار أندرويد", "رقم الإصدار", "الطراز", "اسم الجهاز", "معلومات البرنامج",
    )

    /** T-2: navigate a system app by scrolling and tapping, read-only. */
    fun settingsAbout(): UiTask = UiTask(
        id = "t2-settings-${System.currentTimeMillis()}",
        name = "T-2 Settings: open About phone",
        allowedPackages = setOf("com.android.settings"),
        steps = listOf(
            Step.Launch("com.android.settings"),
            Step.ScrollTo(Selector(textContains = ABOUT_LABELS), "scroll to About phone", maxScrolls = 15),
            Step.Tap(Selector(textContains = ABOUT_LABELS), "open About phone"),
            Step.Check(Verification.ElementPresent(Selector(textContains = ABOUT_PROOF)), "device details are shown"),
        ),
    )

    /** T-7: open the chat app (it resumes the conversation the user left open) and read it. */
    fun openChat(target: ChatTarget): UiTask = UiTask(
        id = "t7-read-${target.packageName}-${System.currentTimeMillis()}",
        name = "T-7 open and read chat (${target.label})",
        allowedPackages = setOf(target.packageName),
        steps = listOf(
            Step.Launch(target.packageName),
            Step.WaitFor(Verification.ElementPresent(target.profile.input), "a conversation is open (message field visible)"),
        ),
    )

    /** T-8 / T-9: type the approved reply and send it once, then confirm it on screen. */
    fun sendReply(target: ChatTarget, conversation: String, message: String, effectKey: String): UiTask {
        val base = ChatRecipes.sendInOpenChat(
            taskId = "t8-send-${target.packageName}-${System.currentTimeMillis()}",
            profile = target.profile,
            appPackage = target.packageName,
            conversationLabel = conversation,
            message = message,
            effectKey = effectKey,
        )
        return base.copy(name = "T-8 send reply (${target.label})", steps = listOf(Step.Launch(target.packageName)) + base.steps)
    }
}
