package dev.vilquer.petcarescheduler.core.domain.care

import java.time.Instant

enum class CarePlanMaterializationStatus { ACTIVE, EXHAUSTED, SUPERSEDED }

data class CarePlanMaterializationCursor(
    val planId: CarePlanId,
    val scheduleRevision: Int,
    val nextSequence: Long?,
    val nextDueAt: Instant?,
    val materializedThrough: Instant? = null,
    val status: CarePlanMaterializationStatus,
    val version: Long? = null,
    val updatedAt: Instant,
) {
    init {
        require(scheduleRevision >= 0) { "care_cursor_revision_invalid" }
        require((nextSequence == null) == (nextDueAt == null)) { "care_cursor_next_slot_incomplete" }
        require(status != CarePlanMaterializationStatus.ACTIVE || nextSequence != null) { "care_cursor_active_without_next_slot" }
        require(status == CarePlanMaterializationStatus.ACTIVE || nextSequence == null) { "care_cursor_terminal_with_next_slot" }
        require(nextSequence == null || nextSequence >= 0) { "care_cursor_sequence_invalid" }
        require(materializedThrough == null || nextDueAt == null || nextDueAt.isAfter(materializedThrough)) {
            "care_cursor_did_not_advance"
        }
    }

    fun supersede(at: Instant) = copy(
        nextSequence = null,
        nextDueAt = null,
        status = CarePlanMaterializationStatus.SUPERSEDED,
        updatedAt = at,
    )
}

data class CarePlanMaterializationBatch(
    val occurrences: List<CareOccurrence>,
    val cursor: CarePlanMaterializationCursor,
)
