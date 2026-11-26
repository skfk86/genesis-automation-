package com.genesis.automation.ui

import kotlinx.serialization.Serializable

@Serializable
enum class Direction { INCOMING, OUTGOING, UNKNOWN }

@Serializable
data class ChatMessage(val text: String, val direction: Direction, val top: Int)

@Serializable
data class ChatScreen(
    val messages: List<ChatMessage>,
    val inputFound: Boolean,
    val sendFound: Boolean,
    /** True when the conversation is laid out right-to-left (outgoing bubbles on the left). */
    val rtl: Boolean,
) {
    fun lastIncoming(): ChatMessage? = messages.lastOrNull { it.direction == Direction.INCOMING }
}

/**
 * Describes where things are in one messaging app. Every field has a generic fallback, so a
 * profile with only [packageName] still works on simple chat screens.
 */
@Serializable
data class ChatProfile(
    val name: String,
    val packageName: String?,
    val input: Selector,
    val send: Selector,
    /** Resource ids of message text views, when the app exposes them. */
    val messageTextIds: List<String> = emptyList(),
) {
    companion object {
        private val SEND_WORDS = listOf("Send", "إرسال", "ارسال", "أرسل", "ارسل")

        /** Works on any chat-like screen: one text field at the bottom and a send button. */
        val GENERIC = ChatProfile(
            name = "generic",
            packageName = null,
            input = Selector(roles = setOf(Role.TEXT_FIELD), editable = true, region = Region.BOTTOM, allowRoleOnly = true),
            send = Selector(descriptions = SEND_WORDS, texts = SEND_WORDS, roles = setOf(Role.BUTTON, Role.IMAGE)),
        )

        /**
         * WhatsApp (com.whatsapp). Ids observed in public accessibility dumps; not guaranteed and
         * re-checked on the real device in test T-7. Fallbacks keep it working if they change.
         */
        val WHATSAPP = ChatProfile(
            name = "whatsapp",
            packageName = "com.whatsapp",
            input = Selector(
                resourceIds = listOf("com.whatsapp:id/entry"),
                roles = setOf(Role.TEXT_FIELD), editable = true, region = Region.BOTTOM, allowRoleOnly = true,
            ),
            send = Selector(
                resourceIds = listOf("com.whatsapp:id/send"),
                descriptions = SEND_WORDS, roles = setOf(Role.BUTTON, Role.IMAGE),
            ),
            messageTextIds = listOf("com.whatsapp:id/message_text"),
        )

        /** The fixture chat app used in emulator tests. */
        val FIXTURE = ChatProfile(
            name = "fixture",
            packageName = "com.genesis.fixture.chat",
            input = Selector(resourceIds = listOf("com.genesis.fixture.chat:id/input"), roles = setOf(Role.TEXT_FIELD), editable = true, region = Region.BOTTOM, allowRoleOnly = true),
            send = Selector(resourceIds = listOf("com.genesis.fixture.chat:id/send"), descriptions = SEND_WORDS, texts = SEND_WORDS, roles = setOf(Role.BUTTON, Role.IMAGE)),
            messageTextIds = listOf("com.genesis.fixture.chat:id/message_text"),
        )

        fun forPackage(pkg: String): ChatProfile = listOf(WHATSAPP, FIXTURE).firstOrNull { it.packageName == pkg } ?: GENERIC
    }
}

/**
 * Turns a chat screen into a list of messages with a direction. Direction comes from layout,
 * not from the app: outgoing bubbles sit on the same side as the send button (right in
 * left-to-right layouts, left in right-to-left ones).
 */
class ChatScreenReader(private val profile: ChatProfile) {

    fun read(s: ScreenSnapshot): ChatScreen {
        val input = Locator.find(s, profile.input)?.element
        val send = Locator.find(s, profile.send)?.element
        val rtl = when {
            send != null -> send.bounds.centerX < s.screenWidth / 2
            input != null -> input.bounds.right < s.screenWidth * 0.9 && input.bounds.left > s.screenWidth * 0.1
            else -> false
        }
        val bottomLimit = input?.bounds?.top ?: (s.screenHeight * 0.9).toInt()
        val topLimit = (s.screenHeight * 0.12).toInt() // skip the toolbar (contact name, icons)

        val candidates = s.visibleElements.filter { e ->
            val text = e.text?.trim().orEmpty()
            text.isNotEmpty() && !e.editable && !e.password && e.role != Role.BUTTON &&
                e.bounds.top >= topLimit && e.bounds.bottom <= bottomLimit &&
                !TIME.matches(text) && !DATE_HEADER.any { it.equals(text, ignoreCase = true) } &&
                (profile.messageTextIds.isEmpty() || e.resourceId == null || e.resourceId in profile.messageTextIds)
        }
        // Prefer known message ids when the app exposes them.
        val withIds = candidates.filter { it.resourceId != null && it.resourceId in profile.messageTextIds }
        val chosen = withIds.ifEmpty { candidates }

        val messages = chosen.sortedBy { it.bounds.top }.map { e ->
            ChatMessage(e.text!!.trim(), directionOf(e, s.screenWidth, rtl), e.bounds.top)
        }
        return ChatScreen(messages, inputFound = input != null, sendFound = send != null, rtl = rtl)
    }

    private fun directionOf(e: UiElement, width: Int, rtl: Boolean): Direction {
        val leftGap = e.bounds.left
        val rightGap = width - e.bounds.right
        val margin = width * 0.04
        val onRight = rightGap + margin < leftGap
        val onLeft = leftGap + margin < rightGap
        return when {
            onRight -> if (rtl) Direction.INCOMING else Direction.OUTGOING
            onLeft -> if (rtl) Direction.OUTGOING else Direction.INCOMING
            else -> Direction.UNKNOWN
        }
    }

    private companion object {
        val TIME = Regex("^\\d{1,2}[:٫.]\\d{2}(\\s?[AaPp][Mm]|\\s?[صم])?$")
        val DATE_HEADER = listOf("Today", "Yesterday", "اليوم", "أمس", "الأمس")
    }
}
