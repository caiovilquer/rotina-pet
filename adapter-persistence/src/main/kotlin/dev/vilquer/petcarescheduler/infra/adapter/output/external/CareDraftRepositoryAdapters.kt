package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.assistant.AssistantFeedback
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraft
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftAction
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.AiInteractionJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.AssistantFeedbackJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareDraftActionJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareDraftJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers.CareDraftJsonCodec
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.AiInteractionJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.AssistantFeedbackJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CareDraftActionJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CareDraftJpaRepository
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareDraftRepositoryPort
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class CareDraftRepositoryAdapter(
    private val jpa: CareDraftJpaRepository,
    private val actions: CareDraftActionJpaRepository,
    private val feedback: AssistantFeedbackJpaRepository,
    private val codec: CareDraftJsonCodec,
) : CareDraftRepositoryPort {
    override fun save(draft: CareDraft) = jpa.saveAndFlush(draft.toJpa()).toDomain()

    override fun findByIdAndHousehold(id: CareDraftId, householdId: HouseholdId) =
        jpa.findByIdAndHouseholdId(id.value, householdId.value)?.toDomain()

    override fun findByIdAndHouseholdForUpdate(id: CareDraftId, householdId: HouseholdId) =
        jpa.findByHouseholdForUpdate(id.value, householdId.value)?.toDomain()

    override fun listByHousehold(householdId: HouseholdId, page: Int, size: Int) =
        jpa.findAllByHouseholdIdOrderByUpdatedAtDesc(householdId.value, PageRequest.of(page, size)).content.map { it.toDomain() }

    override fun countByHousehold(householdId: HouseholdId) = jpa.countByHouseholdId(householdId.value)
    override fun findActionByRequestId(requestId: java.util.UUID) = actions.findByRequestId(requestId)?.toDomain()
    override fun saveAction(action: CareDraftAction) = actions.save(action.toJpa()).toDomain()
    override fun saveFeedback(feedback: AssistantFeedback): AssistantFeedback {
        this.feedback.save(feedback.toJpa())
        return feedback
    }

    private fun CareDraftJpa.toDomain() = CareDraft(
        id = CareDraftId(id),
        version = version,
        householdId = HouseholdId(householdId),
        actorTutorId = TutorId(actorTutorId),
        channel = channel,
        externalMessageId = externalMessageId,
        status = status,
        inputType = inputType,
        inputHash = inputHash,
        fields = codec.fields(structuredPayload),
        evidence = codec.evidence(evidence),
        missingFields = codec.missing(missingFields),
        warnings = codec.warnings(warnings),
        provenance = codec.provenance(fieldProvenance),
        provider = provider,
        model = model,
        promptVersion = promptVersion,
        planId = planId?.let(::CarePlanId),
        failureCode = failureCode,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
        confirmedAt = confirmedAt,
    )

    private fun CareDraft.toJpa() = CareDraftJpa().also {
        it.id = id.value; it.version = version; it.householdId = householdId.value; it.actorTutorId = actorTutorId.value
        it.channel = channel; it.externalMessageId = externalMessageId; it.status = status; it.inputType = inputType
        it.inputHash = inputHash; it.structuredPayload = codec.fields(fields); it.evidence = codec.evidence(evidence)
        it.missingFields = codec.missing(missingFields); it.warnings = codec.warnings(warnings)
        it.fieldProvenance = codec.provenance(provenance); it.provider = provider; it.model = model
        it.promptVersion = promptVersion; it.planId = planId?.value; it.failureCode = failureCode
        it.createdAt = createdAt; it.updatedAt = updatedAt; it.expiresAt = expiresAt; it.confirmedAt = confirmedAt
    }

    private fun CareDraftActionJpa.toDomain() = CareDraftAction(
        id = id, draftId = CareDraftId(draftId), requestId = requestId, actorTutorId = TutorId(actorTutorId),
        channel = channel, action = action, previousStatus = previousStatus, newStatus = newStatus,
        previousVersion = previousVersion, newVersion = newVersion, happenedAt = happenedAt,
    )

    private fun CareDraftAction.toJpa() = CareDraftActionJpa().also {
        it.id = id; it.draftId = draftId.value; it.requestId = requestId; it.actorTutorId = actorTutorId.value
        it.channel = channel; it.action = action; it.previousStatus = previousStatus; it.newStatus = newStatus
        it.previousVersion = previousVersion; it.newVersion = newVersion; it.happenedAt = happenedAt
    }

    private fun AssistantFeedback.toJpa() = AssistantFeedbackJpa().also {
        it.id = id; it.draftId = draftId.value; it.householdId = householdId.value; it.actorTutorId = actorTutorId.value
        it.positive = positive; it.correctedFields = codec.fieldSet(correctedFields); it.reason = reason
        it.comment = comment; it.createdAt = createdAt
    }
}

@Repository
class AiInteractionRepositoryAdapter(
    private val jpa: AiInteractionJpaRepository,
) : AiInteractionRepositoryPort {
    override fun save(interaction: AiInteraction): AiInteraction {
        jpa.save(AiInteractionJpa().also {
            it.id = interaction.id; it.draftId = interaction.draftId?.value; it.householdId = interaction.householdId.value
            it.actorTutorId = interaction.actorTutorId.value; it.operation = interaction.operation; it.channel = interaction.channel
            it.provider = interaction.provider; it.model = interaction.model; it.promptVersion = interaction.promptVersion
            it.inputTokens = interaction.inputTokens; it.outputTokens = interaction.outputTokens
            it.latencyMillis = interaction.latencyMillis; it.outcome = interaction.outcome; it.errorCode = interaction.errorCode
            it.createdAt = interaction.createdAt
        })
        return interaction
    }
}
