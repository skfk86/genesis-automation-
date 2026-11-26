package com.genesis.core.model

/**
 * Risk classification of an action. Ordering matters: later entries are riskier.
 *
 * - [LOW]: opening apps, navigation, preparing content, local operations.
 * - [MEDIUM]: reading authorized data, processing notifications, organizing user content.
 * - [HIGH]: sending messages, publishing, changing external application data.
 * - [CRITICAL]: payments, security-sensitive account changes, destructive operations.
 *   Never executed autonomously.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}
