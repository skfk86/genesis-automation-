package com.genesis.automation.ui

import kotlinx.serialization.Serializable

/** Vertical part of the screen an element is expected in. Used to break ties. */
@Serializable
enum class Region { ANY, TOP, BOTTOM }

/**
 * How to find an element, with fallbacks in order of reliability:
 * resource id, then content description, then exact text, then text fragment, then role only.
 * Every list holds alternatives (for example the English and Arabic label of the same button).
 */
@Serializable
data class Selector(
    val resourceIds: List<String> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
    val textContains: List<String> = emptyList(),
    val roles: Set<Role> = emptySet(),
    val editable: Boolean? = null,
    val clickable: Boolean? = null,
    val region: Region = Region.ANY,
    /** When nothing else matches, accept the only element with a matching role. */
    val allowRoleOnly: Boolean = false,
) {
    init {
        require(
            resourceIds.isNotEmpty() || descriptions.isNotEmpty() || texts.isNotEmpty() ||
                textContains.isNotEmpty() || roles.isNotEmpty() || editable != null,
        ) { "A selector needs at least one criterion" }
    }

    override fun toString(): String = buildList {
        if (resourceIds.isNotEmpty()) add("id=${resourceIds.joinToString("|")}")
        if (descriptions.isNotEmpty()) add("desc=${descriptions.joinToString("|")}")
        if (texts.isNotEmpty()) add("text=${texts.joinToString("|")}")
        if (textContains.isNotEmpty()) add("contains=${textContains.joinToString("|")}")
        if (roles.isNotEmpty()) add("role=${roles.joinToString("|")}")
        if (editable != null) add("editable=$editable")
    }.joinToString(", ", prefix = "Selector(", postfix = ")")
}

/** Which rule found the element. Anything after [RESOURCE_ID] means the expected id was missing. */
@Serializable
enum class MatchStrategy { RESOURCE_ID, DESCRIPTION, TEXT, TEXT_CONTAINS, ROLE_ONLY }

data class LocateResult(
    val element: UiElement,
    val strategy: MatchStrategy,
    /** How many elements matched the winning rule. More than one means the choice was a tie-break. */
    val candidates: Int,
) {
    /** The selector named resource ids but the element was found another way: the UI has changed. */
    fun indicatesUiChange(selector: Selector): Boolean =
        selector.resourceIds.isNotEmpty() && strategy != MatchStrategy.RESOURCE_ID
}

object Locator {

    fun find(snapshot: ScreenSnapshot, selector: Selector): LocateResult? {
        val base = snapshot.visibleElements.filter { matchesFlags(it, selector) }
        // An exact resource id is trusted whatever the role; weaker rules also need the role to match.
        val roleFiltered = if (selector.roles.isEmpty()) base else base.filter { it.role in selector.roles }
        val rules: List<Pair<MatchStrategy, (UiElement) -> Boolean>> = listOf(
            MatchStrategy.RESOURCE_ID to { e -> e.resourceId != null && e.resourceId in selector.resourceIds },
            MatchStrategy.DESCRIPTION to { e -> selector.descriptions.any { d -> e.description.equalsNormalized(d) } },
            MatchStrategy.TEXT to { e -> !e.password && selector.texts.any { t -> e.text.equalsNormalized(t) } },
            MatchStrategy.TEXT_CONTAINS to { e ->
                !e.password && selector.textContains.any { t ->
                    e.text.containsNormalized(t) || e.description.containsNormalized(t)
                }
            },
        )
        for ((strategy, rule) in rules) {
            val pool = if (strategy == MatchStrategy.RESOURCE_ID) base else roleFiltered
            val matches = pool.filter(rule)
            if (matches.isNotEmpty()) return LocateResult(pick(matches, selector), strategy, matches.size)
        }
        if (selector.allowRoleOnly && selector.roles.isNotEmpty()) {
            val matches = roleFiltered
            if (matches.isNotEmpty()) return LocateResult(pick(matches, selector), MatchStrategy.ROLE_ONLY, matches.size)
        }
        return null
    }

    private fun matchesFlags(e: UiElement, s: Selector): Boolean =
        (s.editable == null || e.editable == s.editable) &&
            (s.clickable == null || e.clickable == s.clickable) &&
            e.enabled

    /** Tie-break: prefer the requested region, then the lowest element (chat inputs and send buttons sit at the bottom). */
    private fun pick(matches: List<UiElement>, s: Selector): UiElement = when (s.region) {
        Region.TOP -> matches.minBy { it.bounds.top }
        Region.BOTTOM, Region.ANY -> matches.maxBy { it.bounds.bottom }
    }
}

internal fun String.normalizedForMatch(): String =
    trim().lowercase().replace(Regex("\\s+"), " ")
        // Arabic letter variants that commonly differ between app versions.
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

private fun String?.equalsNormalized(other: String): Boolean =
    this != null && normalizedForMatch() == other.normalizedForMatch()

private fun String?.containsNormalized(other: String): Boolean =
    this != null && other.isNotBlank() && normalizedForMatch().contains(other.normalizedForMatch())
