package dev.vilquer.petcarescheduler.core.domain.care

import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.math.BigDecimal

@JvmInline value class CareOccurrenceId(val value: UUID)

enum class CareOccurrenceStatus { SCHEDULED, COMPLETED, CANCELLED }

data class CareOccurrence(
    val id: CareOccurrenceId,
    val version: Long? = null,
    val planId: CarePlanId,
    val scheduleRevision: Int,
    val householdId: HouseholdId,
    val tutorId: TutorId,
    val petId: PetId,
    val responsibleTutorId: TutorId,
    val sequence: Long,
    val type: EventType,
    val title: String,
    val instructions: String? = null,
    val dueAt: Instant,
    val zoneId: ZoneId = ZoneId.of("UTC"),
    val status: CareOccurrenceStatus,
    val critical: Boolean = false,
    val escalationDelayMinutes: Int? = null,
    val escalationTutorId: TutorId? = null,
    val estimatedCostAmount: BigDecimal? = null,
    val estimatedCostCurrency: String? = null,
    val completedAt: Instant? = null,
    val completedByTutorId: TutorId? = null,
    val completionNote: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val legacyEventId: Long? = null,
) {
    init {
        require(sequence >= 0) { "care_occurrence_sequence_invalid" }
        require(scheduleRevision >= 0) { "care_occurrence_revision_invalid" }
        require(title.isNotBlank() && title.length <= 120) { "care_occurrence_title_invalid" }
        require(completionNote == null || completionNote.length <= 500) { "care_occurrence_note_invalid" }
        require(
            status != CareOccurrenceStatus.COMPLETED ||
                (completedAt != null && completedByTutorId != null),
        ) { "care_occurrence_completion_incomplete" }
        require(critical == (escalationDelayMinutes != null)) { "care_occurrence_escalation_configuration_invalid" }
        require(escalationDelayMinutes == null || escalationDelayMinutes in 15..10_080) { "care_occurrence_escalation_delay_invalid" }
        require(!critical || escalationTutorId != null) { "care_occurrence_escalation_recipient_required" }
        require(estimatedCostAmount == null || (estimatedCostAmount > BigDecimal.ZERO && estimatedCostAmount.scale() <= 2)) {
            "care_occurrence_estimated_cost_invalid"
        }
        require((estimatedCostAmount == null) == (estimatedCostCurrency == null)) { "care_occurrence_estimated_currency_required" }
    }

    fun complete(actor: TutorId, at: Instant, note: String?): CareOccurrence {
        require(status == CareOccurrenceStatus.SCHEDULED) { "care_occurrence_not_scheduled" }
        return copy(
            status = CareOccurrenceStatus.COMPLETED,
            completedAt = at,
            completedByTutorId = actor,
            completionNote = note?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = at,
        )
    }

    fun reopen(actor: TutorId, at: Instant, undoWindow: Duration): CareOccurrence {
        require(status == CareOccurrenceStatus.COMPLETED) { "care_occurrence_not_completed" }
        require(completedByTutorId == actor) { "care_occurrence_undo_actor_mismatch" }
        require(completedAt != null && !at.isAfter(completedAt.plus(undoWindow))) { "care_occurrence_undo_expired" }
        return copy(
            status = CareOccurrenceStatus.SCHEDULED,
            completedAt = null,
            completedByTutorId = null,
            completionNote = null,
            updatedAt = at,
        )
    }

    fun cancel(at: Instant): CareOccurrence {
        require(status == CareOccurrenceStatus.SCHEDULED) { "care_occurrence_not_scheduled" }
        return copy(status = CareOccurrenceStatus.CANCELLED, updatedAt = at)
    }

    fun assignTo(responsible: TutorId, at: Instant): CareOccurrence =
        copy(responsibleTutorId = responsible, updatedAt = at)
}
