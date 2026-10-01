package dev.vilquer.petcarescheduler.usecase.contract.drivingports

import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.usecase.command.IngestWhatsAppEventCommand
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppConnectionResult
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppLinkTokenResult

interface WhatsAppConnectionUseCase {
    fun status(access: HouseholdAccess): WhatsAppConnectionResult
    fun createLinkToken(access: HouseholdAccess): WhatsAppLinkTokenResult
    fun revoke(access: HouseholdAccess)
}

interface WhatsAppInboundUseCase {
    fun ingest(command: IngestWhatsAppEventCommand): Boolean
    fun processBatch(): Int
}

fun interface WhatsAppOutboxUseCase {
    fun relayBatch(): Int
}
