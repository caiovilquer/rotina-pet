package dev.vilquer.petcarescheduler.core.domain.assistant

import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

@JvmInline
value class CareDraftId(val value: UUID)

enum class CareDraftChannel { WEB, WHATSAPP }
enum class CareDraftInputType { TEXT, AUDIO, IMAGE, PDF }
enum class CareDraftStatus { PROCESSING, NEEDS_INPUT, READY, CONFIRMED, CANCELLED, EXPIRED, FAILED }
enum class CareDraftFieldProvenance { EXPLICIT, NORMALIZED, SYSTEM_DEFAULT, NEEDS_REVIEW, MISSING }

enum class CareDraftField {
    PET,
    TYPE,
    TITLE,
    INSTRUCTIONS,
    START_AT,
    TIMEZONE,
    SCHEDULE,
    REMINDER,
    RESPONSIBLE,
    CRITICAL,
    ESCALATION,
    ESTIMATED_COST,
}

data class CareDraftWarning(
    val code: String,
    val message: String,
    val blocking: Boolean = false,
) {
    init {
        require(code.matches(Regex("^[A-Z0-9_]{3,80}$"))) { "care_draft_warning_code_invalid" }
        require(message.isNotBlank() && message.length <= 500) { "care_draft_warning_message_invalid" }
    }
}

data class CareDraftFields(
    val petId: PetId? = null,
    val type: EventType? = null,
    val title: String? = null,
    val instructions: String? = null,
    val startAt: Instant? = null,
    val zoneId: ZoneId? = null,
    val scheduleRule: ScheduleRule? = null,
    val reminderMinutesBefore: Int = 0,
    val responsibleTutorId: TutorId? = null,
    val critical: Boolean = false,
    val escalationDelayMinutes: Int? = null,
    val escalationTutorId: TutorId? = null,
    val estimatedCostAmount: BigDecimal? = null,
    val estimatedCostCurrency: String? = null,
) {
    init {
        require(title == null || (title.isNotBlank() && title.length <= 120)) { "care_draft_title_invalid" }
        require(instructions == null || instructions.length <= 2_000) { "care_draft_instructions_invalid" }
        require(reminderMinutesBefore in 0..10_080) { "care_draft_reminder_invalid" }
        require(scheduleRule?.endAt == null || startAt == null || !scheduleRule.endAt.isBefore(startAt)) {
            "care_draft_end_before_start"
        }
        require(critical == (escalationDelayMinutes != null)) { "care_draft_escalation_configuration_invalid" }
        require(escalationDelayMinutes == null || escalationDelayMinutes in 15..10_080) { "care_draft_escalation_delay_invalid" }
        require(!critical || escalationTutorId != null) { "care_draft_escalation_recipient_required" }
        require(estimatedCostAmount == null || (estimatedCostAmount > BigDecimal.ZERO && estimatedCostAmount <= MAX_COST && estimatedCostAmount.scale() <= 2)) {
            "care_draft_estimated_cost_invalid"
        }
        require((estimatedCostAmount == null) == (estimatedCostCurrency == null)) { "care_draft_estimated_currency_required" }
        estimatedCostCurrency?.let {
            require(it.matches(Regex("^[A-Z]{3}$"))) { "care_draft_estimated_currency_invalid" }
            runCatching { Currency.getInstance(it) }.getOrElse { throw IllegalArgumentException("care_draft_estimated_currency_invalid") }
        }
    }

    fun missingRequired(): Set<CareDraftField> = buildSet {
        if (petId == null) add(CareDraftField.PET)
        if (type == null) add(CareDraftField.TYPE)
        if (title == null) add(CareDraftField.TITLE)
        if (startAt == null) add(CareDraftField.START_AT)
        if (zoneId == null) add(CareDraftField.TIMEZONE)
        if (scheduleRule == null) add(CareDraftField.SCHEDULE)
        if (responsibleTutorId == null) add(CareDraftField.RESPONSIBLE)
    }

    companion object {
        private val MAX_COST = BigDecimal("9999999999.99")
    }
}

data class CareDraft(
    val id: CareDraftId = CareDraftId(UUID.randomUUID()),
    val version: Long? = null,
    val householdId: HouseholdId,
    val actorTutorId: TutorId,
    val channel: CareDraftChannel,
    val externalMessageId: String? = null,
    val status: CareDraftStatus = CareDraftStatus.PROCESSING,
    val inputType: CareDraftInputType,
    val inputHash: String,
    val fields: CareDraftFields,
    val evidence: Map<CareDraftField, String> = emptyMap(),
    val missingFields: Set<CareDraftField> = fields.missingRequired(),
    val warnings: List<CareDraftWarning> = emptyList(),
    val provenance: Map<CareDraftField, CareDraftFieldProvenance> = emptyMap(),
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String,
    val planId: CarePlanId? = null,
    val failureCode: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val confirmedAt: Instant? = null,
) {
    init {
        require(inputHash.matches(Regex("^[a-f0-9]{64}$"))) { "care_draft_input_hash_invalid" }
        require(externalMessageId == null || externalMessageId.length <= 255) { "care_draft_external_message_invalid" }
        require(promptVersion.isNotBlank() && promptVersion.length <= 80) { "care_draft_prompt_version_invalid" }
        require(expiresAt.isAfter(createdAt)) { "care_draft_expiration_invalid" }
        require(evidence.values.all { it.isNotBlank() && it.length <= 500 }) { "care_draft_evidence_invalid" }
        require(missingFields.containsAll(fields.missingRequired())) { "care_draft_missing_fields_inconsistent" }
        require(status != CareDraftStatus.READY || (missingFields.isEmpty() && warnings.none { it.blocking })) {
            "care_draft_ready_invalid"
        }
        require((status == CareDraftStatus.CONFIRMED) == (planId != null && confirmedAt != null)) {
            "care_draft_confirmation_invalid"
        }
        require(failureCode == null || failureCode.length <= 120) { "care_draft_failure_code_invalid" }
    }

    fun applyExtraction(
        extractedFields: CareDraftFields,
        extractedEvidence: Map<CareDraftField, String>,
        extractedMissing: Set<CareDraftField>,
        extractedWarnings: List<CareDraftWarning>,
        extractedProvenance: Map<CareDraftField, CareDraftFieldProvenance>,
        provider: String,
        model: String,
        at: Instant,
    ): CareDraft {
        require(status == CareDraftStatus.PROCESSING) { "care_draft_not_processing" }
        val missing = extractedMissing + extractedFields.missingRequired()
        return copy(
            status = resolvedStatus(missing, extractedWarnings),
            fields = extractedFields,
            evidence = extractedEvidence,
            missingFields = missing,
            warnings = extractedWarnings,
            provenance = extractedProvenance.withMissing(missing),
            provider = provider.take(80),
            model = model.take(120),
            failureCode = null,
            updatedAt = at,
        )
    }

    fun revise(revisedFields: CareDraftFields, at: Instant): CareDraft {
        require(status == CareDraftStatus.NEEDS_INPUT || status == CareDraftStatus.READY) { "care_draft_not_revisable" }
        require(at.isBefore(expiresAt)) { "care_draft_expired" }
        val missing = revisedFields.missingRequired()
        val remainingWarnings = warnings.filterNot { it.blocking }
        val revisedProvenance = provenance.toMutableMap().apply {
            CareDraftField.entries.forEach { field ->
                this[field] = if (field in missing) CareDraftFieldProvenance.MISSING else CareDraftFieldProvenance.EXPLICIT
            }
        }
        return copy(
            status = resolvedStatus(missing, remainingWarnings),
            fields = revisedFields,
            missingFields = missing,
            warnings = remainingWarnings,
            provenance = revisedProvenance,
            failureCode = null,
            updatedAt = at,
        )
    }

    fun confirm(confirmedPlanId: CarePlanId, at: Instant): CareDraft {
        require(status == CareDraftStatus.READY) { "care_draft_not_ready" }
        require(at.isBefore(expiresAt)) { "care_draft_expired" }
        return copy(status = CareDraftStatus.CONFIRMED, planId = confirmedPlanId, confirmedAt = at, updatedAt = at)
    }

    fun cancel(at: Instant): CareDraft {
        require(status !in TERMINAL_STATUSES) { "care_draft_not_cancellable" }
        return copy(status = CareDraftStatus.CANCELLED, updatedAt = at)
    }

    fun fail(code: String, warning: CareDraftWarning, at: Instant): CareDraft {
        require(status == CareDraftStatus.PROCESSING) { "care_draft_not_processing" }
        return copy(status = CareDraftStatus.FAILED, warnings = warnings + warning, failureCode = code.take(120), updatedAt = at)
    }

    fun expire(at: Instant): CareDraft {
        require(status !in TERMINAL_STATUSES) { "care_draft_not_expirable" }
        require(!at.isBefore(expiresAt)) { "care_draft_not_expired" }
        return copy(status = CareDraftStatus.EXPIRED, updatedAt = at)
    }

    private fun Map<CareDraftField, CareDraftFieldProvenance>.withMissing(
        missing: Set<CareDraftField>,
    ): Map<CareDraftField, CareDraftFieldProvenance> = toMutableMap().apply {
        missing.forEach { this[it] = CareDraftFieldProvenance.MISSING }
    }

    private fun resolvedStatus(missing: Set<CareDraftField>, warnings: List<CareDraftWarning>) =
        if (missing.isEmpty() && warnings.none { it.blocking }) CareDraftStatus.READY else CareDraftStatus.NEEDS_INPUT

    companion object {
        private val TERMINAL_STATUSES = setOf(
            CareDraftStatus.CONFIRMED,
            CareDraftStatus.CANCELLED,
            CareDraftStatus.EXPIRED,
            CareDraftStatus.FAILED,
        )
    }
}

enum class CareDraftActionType { GENERATED, EXTRACTED, CORRECTED, CONFIRMED, CANCELLED, EXPIRED, FAILED, FEEDBACK }

data class CareDraftAction(
    val id: UUID = UUID.randomUUID(),
    val draftId: CareDraftId,
    val requestId: UUID? = null,
    val actorTutorId: TutorId,
    val channel: CareDraftChannel,
    val action: CareDraftActionType,
    val previousStatus: CareDraftStatus?,
    val newStatus: CareDraftStatus,
    val previousVersion: Long?,
    val newVersion: Long?,
    val happenedAt: Instant,
)

enum class AiInteractionOutcome { SUCCESS, PROVIDER_ERROR, INVALID_OUTPUT, DISABLED }

data class AiInteraction(
    val id: UUID = UUID.randomUUID(),
    val draftId: CareDraftId?,
    val householdId: HouseholdId,
    val actorTutorId: TutorId,
    val operation: String,
    val channel: CareDraftChannel,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val latencyMillis: Long,
    val outcome: AiInteractionOutcome,
    val errorCode: String? = null,
    val createdAt: Instant,
) {
    init {
        require(operation.isNotBlank() && operation.length <= 80) { "ai_interaction_operation_invalid" }
        require(latencyMillis >= 0) { "ai_interaction_latency_invalid" }
        require(inputTokens == null || inputTokens >= 0) { "ai_interaction_input_tokens_invalid" }
        require(outputTokens == null || outputTokens >= 0) { "ai_interaction_output_tokens_invalid" }
    }
}

data class AssistantFeedback(
    val id: UUID = UUID.randomUUID(),
    val draftId: CareDraftId,
    val householdId: HouseholdId,
    val actorTutorId: TutorId,
    val positive: Boolean,
    val correctedFields: Set<CareDraftField> = emptySet(),
    val reason: String? = null,
    val comment: String? = null,
    val createdAt: Instant,
) {
    init {
        require(reason == null || reason.matches(Regex("^[A-Z0-9_]{3,80}$"))) { "assistant_feedback_reason_invalid" }
        require(comment == null || comment.length <= 1_000) { "assistant_feedback_comment_invalid" }
    }
}
