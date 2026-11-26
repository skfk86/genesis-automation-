package com.genesis.automation.ui

import com.genesis.core.model.RiskLevel

sealed interface GuardVerdict {
    data object Allow : GuardVerdict
    data class Deny(val reason: String) : GuardVerdict
}

/**
 * Hard limits checked before every operation. They cannot be turned off by a recipe, a model
 * suggestion, or text read from the screen.
 */
class SafetyPolicy(
    /** Apps this task may touch. Everything else is off limits. */
    private val allowedPackages: Set<String>,
    private val extraBlockedPackages: Set<String> = emptySet(),
) {
    fun check(op: UiOp, screen: ScreenSnapshot?): GuardVerdict {
        if (op is UiOp.LaunchApp) return checkPackage(op.packageName)
        if (screen == null) return GuardVerdict.Deny("screen not readable")
        checkPackage(screen.packageName).let { if (it is GuardVerdict.Deny) return it }
        if (screen.secure) return GuardVerdict.Deny("protected screen")
        return when (op) {
            is UiOp.SetText ->
                if (op.target.password) GuardVerdict.Deny("password field") else GuardVerdict.Allow
            is UiOp.Tap -> {
                val label = op.target.visibleLabel?.normalizedForMatch().orEmpty()
                val hit = CRITICAL_WORDS.firstOrNull { label.contains(it.normalizedForMatch()) }
                if (hit != null) GuardVerdict.Deny("critical action '$hit' is never automated") else GuardVerdict.Allow
            }
            else -> GuardVerdict.Allow
        }
    }

    private fun checkPackage(pkg: String): GuardVerdict = when {
        pkg in ALWAYS_BLOCKED || pkg in extraBlockedPackages -> GuardVerdict.Deny("app $pkg is always blocked")
        BLOCKED_PREFIXES.any { pkg.startsWith(it) } -> GuardVerdict.Deny("app $pkg is always blocked")
        pkg !in allowedPackages -> GuardVerdict.Deny("app $pkg is not allowed for this task")
        else -> GuardVerdict.Allow
    }

    companion object {
        /** Words on buttons the engine must never press by itself, in English and Arabic. */
        val CRITICAL_WORDS = listOf(
            "pay", "buy", "purchase", "transfer", "delete", "remove account", "log out", "logout", "sign out",
            "block", "report", "unfriend", "change password", "uninstall", "factory reset",
            "دفع", "ادفع", "شراء", "اشتر", "تحويل", "حذف", "احذف", "تسجيل الخروج", "خروج من الحساب",
            "حظر", "ابلاغ", "إبلاغ", "إلغاء الصداقة", "تغيير كلمة المرور", "إلغاء التثبيت", "إعادة ضبط المصنع",
        )

        /** Never automated, even if a user adds them: package installer, GENESIS itself, password managers. */
        val ALWAYS_BLOCKED = setOf(
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.genesis.app",
            "com.android.settings.accessibility",
        )
        val BLOCKED_PREFIXES = listOf("com.x8bit.bitwarden", "com.lastpass", "com.onepassword", "com.agilebits", "com.paypal", "com.google.android.apps.walletnfcrel")
    }
}

/** Something that changes the outside world and therefore needs a person's yes. */
data class ApprovalRequest(
    val effectKey: String,
    val app: String,
    val title: String,
    /** Exactly what will be sent or published. Shown in full to the user. */
    val content: String,
    val risk: RiskLevel,
)

sealed interface ApprovalDecision {
    /** Approves exactly [ApprovalRequest.content]. Editing happens before the task starts, so what is approved is what is typed. */
    data object Approved : ApprovalDecision
    data object Rejected : ApprovalDecision
}

/**
 * In this prototype every external effect asks. A pre-authorised, scoped standing rule
 * (decided in Phase 1, section 9.3) plugs in here later without changing the engine.
 */
fun interface ApprovalGate {
    suspend fun request(r: ApprovalRequest): ApprovalDecision
}

/**
 * Human pacing and volume limits. These reduce mistakes and runaway loops; they are not a
 * guarantee against an app restricting the account.
 */
class Pacer(
    private val clock: EngineClock,
    private val minGapMillis: Long = 450,
    private val jitterMillis: Long = 350,
    private val maxEffectsPerWindow: Int = 5,
    private val windowMillis: Long = 10 * 60_000L,
    private val random: () -> Double = Math::random,
) {
    private var lastOpAt = 0L
    private val effectTimes = ArrayDeque<Long>()

    suspend fun beforeOp() {
        val wait = lastOpAt + minGapMillis + (random() * jitterMillis).toLong() - clock.nowMillis()
        if (wait > 0) clock.sleep(wait)
        lastOpAt = clock.nowMillis()
    }

    /** False when the external-effect budget for the window is used up. */
    fun tryReserveEffect(): Boolean {
        val now = clock.nowMillis()
        while (effectTimes.isNotEmpty() && now - effectTimes.first() > windowMillis) effectTimes.removeFirst()
        if (effectTimes.size >= maxEffectsPerWindow) return false
        effectTimes.addLast(now)
        return true
    }
}
