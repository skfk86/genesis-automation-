package com.genesis.automation.ui

/** Proof for chat sends: how many outgoing bubbles show exactly this text. */
class OutgoingTextProof(private val reader: ChatScreenReader, private val text: String) : EffectProof {
    override val label = "outgoing bubble with the message"
    override fun count(s: ScreenSnapshot) =
        reader.read(s).messages.count { it.direction == Direction.OUTGOING && it.text.normalizedForMatch() == text.normalizedForMatch() }
}

/**
 * Generic chat recipes. The same steps work for WhatsApp, the fixture app, or any app the
 * generic profile can read; only the [ChatProfile] differs.
 */
object ChatRecipes {

    /**
     * Type [message] into the open conversation and send it once, with approval.
     * Starts from the conversation screen already open (the user or a previous step opened it).
     */
    fun sendInOpenChat(
        taskId: String,
        profile: ChatProfile,
        appPackage: String,
        conversationLabel: String,
        message: String,
        effectKey: String,
    ): UiTask {
        require(message.isNotBlank()) { "message must not be blank" }
        val reader = ChatScreenReader(profile)
        return UiTask(
            id = taskId,
            name = "send message (${profile.name})",
            allowedPackages = setOf(appPackage),
            steps = listOf(
                Step.WaitFor(Verification.InApp(appPackage), "conversation is open in $appPackage"),
                Step.WaitFor(Verification.ElementPresent(profile.input), "message field visible"),
                Step.TypeText(profile.input, message, "type the reply"),
                Step.Effect(
                    key = effectKey,
                    action = profile.send,
                    app = appPackage,
                    title = "إرسال رسالة إلى $conversationLabel",
                    content = message,
                    proof = OutgoingTextProof(reader, message),
                    notDeliveredEvidence = Verification.FieldContains(profile.input, message),
                    label = "press send once and confirm the bubble",
                ),
            ),
        )
    }
}
