package com.genesis.automation.ui

/** A condition checked against the screen. Used to confirm a step worked, or to prove it did not. */
sealed interface Verification {
    fun check(s: ScreenSnapshot): Boolean
    val label: String

    data class ElementPresent(val selector: Selector) : Verification {
        override fun check(s: ScreenSnapshot) = Locator.find(s, selector) != null
        override val label get() = "present $selector"
    }

    /** Some visible element (not a text field) shows exactly this text. */
    data class TextShown(val text: String, val excludeEditable: Boolean = true) : Verification {
        override fun check(s: ScreenSnapshot) = s.visibleElements.any { e ->
            !(excludeEditable && e.editable) && !e.password &&
                (e.text?.normalizedForMatch() == text.normalizedForMatch())
        }
        override val label get() = "text shown (${text.length} chars)"
    }

    /** The editable element matched by [field] contains this text (for example an unsent draft). */
    data class FieldContains(val field: Selector, val text: String) : Verification {
        override fun check(s: ScreenSnapshot): Boolean {
            val e = Locator.find(s, field)?.element ?: return false
            return e.text?.normalizedForMatch()?.contains(text.normalizedForMatch()) == true
        }
        override val label get() = "field contains draft"
    }

    data class InApp(val packageName: String) : Verification {
        override fun check(s: ScreenSnapshot) = s.packageName == packageName
        override val label get() = "in app $packageName"
    }

    data class Not(val inner: Verification) : Verification {
        override fun check(s: ScreenSnapshot) = !inner.check(s)
        override val label get() = "not(${inner.label})"
    }

    data class All(val parts: List<Verification>) : Verification {
        override fun check(s: ScreenSnapshot) = parts.all { it.check(s) }
        override val label get() = parts.joinToString(" and ") { it.label }
    }

    /** Counts outgoing chat bubbles with this text, to tell "sent once" from "sent twice". */
    data class OutgoingMessageCount(val reader: ChatScreenReader, val text: String, val atLeast: Int) : Verification {
        override fun check(s: ScreenSnapshot) =
            reader.read(s).messages.count { it.direction == Direction.OUTGOING && it.text.normalizedForMatch() == text.normalizedForMatch() } >= atLeast
        override val label get() = "outgoing message shown x$atLeast"
    }
}
