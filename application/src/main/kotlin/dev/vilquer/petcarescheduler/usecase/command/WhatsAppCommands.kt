package dev.vilquer.petcarescheduler.usecase.command

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import java.time.Instant

data class IngestWhatsAppEventCommand(
    val providerEventKey: String,
    val providerMessageId: String,
    val businessPhoneNumberId: String,
    val senderWaId: String?,
    val type: WhatsAppInboundType,
    val content: String?,
    val providerStatus: String? = null,
    val eventAt: Instant,
)
