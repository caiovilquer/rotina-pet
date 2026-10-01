package dev.vilquer.petcarescheduler.core.domain.care

import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.math.BigDecimal
import java.util.Currency

@JvmInline value class CarePlanId(val value: UUID)

data class CarePlan(
    val id: CarePlanId = CarePlanId(UUID.randomUUID()),
    val version: Long? = null,
    val sourceDraftId: UUID? = null,
    val scheduleRevision: Int = 0,
    val householdId: HouseholdId,
    val tutorId: TutorId,
    val petId: PetId,
    val responsibleTutorId: TutorId,
    val type: EventType,
    val title: String,
    val instructions: String? = null,
    val startAt: Instant,
    val zoneId: ZoneId,
    val scheduleRule: ScheduleRule = ScheduleRule.oneTime(),
    val reminderMinutesBefore: Int = 0,
    val critical: Boolean = false,
    val escalationDelayMinutes: Int? = null,
    val escalationTutorId: TutorId? = null,
    val estimatedCostAmount: BigDecimal? = null,
    val estimatedCostCurrency: String? = null,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(title.isNotBlank() && title.length <= 120) { "care_plan_title_invalid" }
        require(instructions == null || instructions.length <= 2_000) { "care_plan_instructions_invalid" }
        require(reminderMinutesBefore in 0..10_080) { "care_plan_reminder_invalid" }
        require(scheduleRevision >= 0) { "care_plan_revision_invalid" }
        require(scheduleRule.endAt == null || !scheduleRule.endAt.isBefore(startAt)) { "care_plan_end_before_start" }
        require(critical == (escalationDelayMinutes != null)) { "care_plan_escalation_configuration_invalid" }
        require(escalationDelayMinutes == null || escalationDelayMinutes in 15..10_080) { "care_plan_escalation_delay_invalid" }
        require(!critical || escalationTutorId != null) { "care_plan_escalation_recipient_required" }
        require(estimatedCostAmount == null || (estimatedCostAmount > BigDecimal.ZERO && estimatedCostAmount <= MAX_COST && estimatedCostAmount.scale() <= 2)) {
            "care_plan_estimated_cost_invalid"
        }
        require((estimatedCostAmount == null) == (estimatedCostCurrency == null)) { "care_plan_estimated_currency_required" }
        estimatedCostCurrency?.let {
            require(it.matches(Regex("^[A-Z]{3}$"))) { "care_plan_estimated_currency_invalid" }
            runCatching { Currency.getInstance(it) }.getOrElse { throw IllegalArgumentException("care_plan_estimated_currency_invalid") }
        }
    }

    fun initialCursor(cutoff: Instant, at: Instant): CarePlanMaterializationCursor {
        val slot = scheduleRule.firstOnOrAfter(startAt, cutoff, zoneId)
        return CarePlanMaterializationCursor(
            planId = id,
            scheduleRevision = scheduleRevision,
            nextSequence = slot?.sequence,
            nextDueAt = slot?.dueAt,
            status = if (slot == null) CarePlanMaterializationStatus.EXHAUSTED else CarePlanMaterializationStatus.ACTIVE,
            updatedAt = at,
        )
    }

    fun materialize(
        cursor: CarePlanMaterializationCursor,
        horizon: Instant,
        generatedAt: Instant,
        maxOccurrences: Int = MAX_OCCURRENCES_PER_BATCH,
    ): CarePlanMaterializationBatch {
        require(cursor.planId == id && cursor.scheduleRevision == scheduleRevision) { "care_cursor_plan_mismatch" }
        require(maxOccurrences in 1..MAX_OCCURRENCES_PER_BATCH) { "care_materialization_batch_invalid" }
        if (!active || cursor.status != CarePlanMaterializationStatus.ACTIVE) {
            return CarePlanMaterializationBatch(emptyList(), cursor)
        }

        var slot = ScheduleSlot(cursor.nextSequence!!, cursor.nextDueAt!!)
        val generated = ArrayList<CareOccurrence>(maxOccurrences)
        while (!slot.dueAt.isAfter(horizon) && generated.size < maxOccurrences) {
            generated += occurrence(slot, generatedAt)
            slot = scheduleRule.next(startAt, slot, zoneId) ?: break
        }

        if (generated.isEmpty()) return CarePlanMaterializationBatch(emptyList(), cursor)
        val last = generated.last()
        val next = scheduleRule.next(startAt, ScheduleSlot(last.sequence, last.dueAt), zoneId)
        val advanced = cursor.copy(
            nextSequence = next?.sequence,
            nextDueAt = next?.dueAt,
            materializedThrough = last.dueAt,
            status = if (next == null) CarePlanMaterializationStatus.EXHAUSTED else CarePlanMaterializationStatus.ACTIVE,
            updatedAt = generatedAt,
        )
        return CarePlanMaterializationBatch(generated, advanced)
    }

    fun deactivate(at: Instant): CarePlan = copy(active = false, updatedAt = at)

    private fun occurrence(slot: ScheduleSlot, generatedAt: Instant) = CareOccurrence(
        id = deterministicOccurrenceId(slot.sequence),
        planId = id,
        scheduleRevision = scheduleRevision,
        householdId = householdId,
        tutorId = tutorId,
        petId = petId,
        responsibleTutorId = responsibleTutorId,
        sequence = slot.sequence,
        type = type,
        title = title,
        instructions = instructions,
        dueAt = slot.dueAt,
        zoneId = zoneId,
        status = CareOccurrenceStatus.SCHEDULED,
        critical = critical,
        escalationDelayMinutes = escalationDelayMinutes,
        escalationTutorId = escalationTutorId,
        estimatedCostAmount = estimatedCostAmount,
        estimatedCostCurrency = estimatedCostCurrency,
        createdAt = generatedAt,
        updatedAt = generatedAt,
    )

    private fun deterministicOccurrenceId(sequence: Long): CareOccurrenceId = CareOccurrenceId(
        UUID.nameUUIDFromBytes("${id.value}:$scheduleRevision:$sequence".toByteArray(StandardCharsets.UTF_8)),
    )

    companion object {
        const val MAX_OCCURRENCES_PER_BATCH = 500
        private val MAX_COST = BigDecimal("9999999999.99")
    }
}
