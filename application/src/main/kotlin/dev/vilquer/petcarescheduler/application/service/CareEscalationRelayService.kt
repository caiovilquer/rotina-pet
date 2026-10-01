package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdActivity
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdActivityType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdTimezone
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.DispatchPendingCareEscalationsUseCase

class CareEscalationRelayService(
    private val outbox: CareEscalationOutboxPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val notifier: NotificationPort,
    private val activities: HouseholdActivityRepositoryPort,
    private val clock: ClockPort,
    private val members: HouseholdMemberRepositoryPort,
) : DispatchPendingCareEscalationsUseCase {
    override fun dispatchPendingCareEscalations() {
        outbox.findPending(MAX_ATTEMPTS, BATCH).forEach { message ->
            val occurrence = occurrences.findByIdAndHousehold(message.occurrenceId, message.householdId)
            if (occurrence?.status != CareOccurrenceStatus.SCHEDULED) {
                message.id?.let { outbox.markCancelled(it, clock.now().toInstant()) }
                return@forEach
            }
            val member = members.findAccess(message.recipientTutorId, message.householdId)
            if (member?.role != dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole.OWNER) {
                message.id?.let { outbox.markCancelled(it, clock.now().toInstant()) }
                return@forEach
            }
            val sent = notifier.sendCareEscalation(CareEscalationNotificationTarget(
                message.recipientEmail, message.petName, message.careTitle, message.dueAt,
                occurrence.zoneId.id,
            ))
            message.id?.let { id ->
                if (sent) {
                    outbox.markSent(id)
                    activities.save(HouseholdActivity(
                        householdId = message.householdId, type = HouseholdActivityType.ESCALATION_SENT,
                        actorTutorId = null, targetTutorId = message.recipientTutorId,
                        petId = occurrence.petId, careOccurrenceId = occurrence.id.value,
                        summary = "Cuidado crítico não confirmado: ${message.careTitle}", happenedAt = clock.now().toInstant(),
                    ))
                } else outbox.incrementAttempts(id)
            }
        }
    }
    companion object { private const val MAX_ATTEMPTS = 5; private const val BATCH = 50 }
}
