package dev.vilquer.petcarescheduler.usecase.result

import java.time.Instant

enum class WhatsAppConnectionStatus { UNAVAILABLE, DISCONNECTED, CONNECTED, REVOKED }

data class WhatsAppConnectionResult(
    val status: WhatsAppConnectionStatus,
    val householdId: String,
    val maskedNumber: String? = null,
    val linkedAt: Instant? = null,
)

data class WhatsAppLinkTokenResult(
    val code: String,
    val deepLink: String,
    val expiresAt: Instant,
)
