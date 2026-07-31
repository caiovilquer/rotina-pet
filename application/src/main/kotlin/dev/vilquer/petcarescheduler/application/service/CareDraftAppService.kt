package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.application.exception.UpstreamServiceException
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteractionOutcome
import dev.vilquer.petcarescheduler.core.domain.assistant.AssistantFeedback
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraft
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftAction
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftActionType
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFieldProvenance
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFields
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftInputType
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftStatus
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftWarning
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdPermission
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.usecase.command.AddCareDraftFeedbackCommand
import dev.vilquer.petcarescheduler.usecase.command.CancelCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.ConfirmCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.CorrectCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.GenerateCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareDraftRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionPetContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedCareDraft
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareDraftUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase
import dev.vilquer.petcarescheduler.usecase.result.CareDraftConfirmationResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftFieldsResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftPageResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftResult
import dev.vilquer.petcarescheduler.usecase.result.CareScheduleRuleResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CareDraftSettings(
    val enabled: Boolean = false,
    val ttl: Duration = Duration.ofHours(24),
    val maxInputCharacters: Int = 4_000,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "care_draft_ttl_invalid" }
        require(maxInputCharacters in 100..20_000) { "care_draft_input_limit_invalid" }
    }
}

class CareDraftAppService(
    private val drafts: CareDraftRepositoryPort,
    private val interactions: AiInteractionRepositoryPort,
    private val extractor: CareInstructionExtractorPort,
    private val carePlans: CarePlanUseCase,
    private val pets: PetRepositoryPort,
    private val members: HouseholdMemberRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val settings: CareDraftSettings,
    private val rateLimiter: RateLimiterService? = null,
) : CareDraftUseCase {

    override fun generate(command: GenerateCareDraftCommand, access: HouseholdAccess): CareDraftResult {
        requireOwner(access)
        val instruction = command.instruction.trim()
        require(instruction.isNotEmpty() && instruction.length <= settings.maxInputCharacters) { "care_draft_instruction_invalid" }
        val now = clock.now(access.zoneId).toInstant()
        transaction.execute {
            val replay = drafts.findActionByRequestId(command.requestId) ?: return@execute null
            drafts.findByIdAndHousehold(replay.draftId, access.householdId)?.toResult()
                ?: throw ConflictException("Identificador de requisição já utilizado")
        }?.let { return it }
        if (!settings.enabled) {
            transaction.execute {
                interactions.save(interaction(null, access, 0, AiInteractionOutcome.DISABLED, "AI_DISABLED", now))
            }
            throw UpstreamServiceException(
                "O assistente está temporariamente desativado; use o formulário manual",
                "AI_PROVIDER_UNAVAILABLE",
            )
        }
        rateLimiter?.check(
            RateLimitAction.ASSISTANT_DRAFT,
            "${access.householdId.value}:${access.actorTutorId.value}",
        )

        val initialFields = CareDraftFields(
            zoneId = access.zoneId,
            responsibleTutorId = access.actorTutorId,
        )
        val initial = CareDraft(
            householdId = access.householdId,
            actorTutorId = access.actorTutorId,
            channel = command.channel,
            externalMessageId = command.externalMessageId,
            inputType = CareDraftInputType.TEXT,
            inputHash = sha256(instruction),
            fields = initialFields,
            provenance = mapOf(
                CareDraftField.TIMEZONE to CareDraftFieldProvenance.SYSTEM_DEFAULT,
                CareDraftField.RESPONSIBLE to CareDraftFieldProvenance.SYSTEM_DEFAULT,
                CareDraftField.REMINDER to CareDraftFieldProvenance.SYSTEM_DEFAULT,
                CareDraftField.CRITICAL to CareDraftFieldProvenance.SYSTEM_DEFAULT,
            ),
            promptVersion = extractor.promptVersion,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(settings.ttl),
        )
        val persisted = transaction.execute {
            val saved = drafts.save(initial)
            drafts.saveAction(action(saved, command.requestId, access, CareDraftActionType.GENERATED, null, saved.status, null, saved.version, now))
            saved
        }

        val request = CareInstructionExtractionRequest(
            instruction = instruction,
            receivedAt = now,
            zoneId = access.zoneId,
            pets = pets.listByHousehold(access.householdId, 0, MAX_PETS_IN_CONTEXT)
                .mapNotNull { pet -> pet.id?.let { CareInstructionPetContext(it, pet.name) } },
            context = AiInvocationContext(access.householdId, access.actorTutorId),
        )
        val startedAt = System.nanoTime()
        val extraction = try {
            extractor.extract(request)
        } catch (exception: RuntimeException) {
            val latency = elapsedMillis(startedAt)
            return failExtraction(persisted.id, exception, access, latency)
        }
        return applyExtraction(persisted.id, extraction, access, elapsedMillis(startedAt))
    }

    override fun get(id: CareDraftId, access: HouseholdAccess): CareDraftResult {
        requireOwner(access)
        return drafts.findByIdAndHousehold(id, access.householdId)?.toResult()
            ?: throw NotFoundException("Rascunho não encontrado")
    }

    override fun list(access: HouseholdAccess, page: Int, size: Int): CareDraftPageResult {
        requireOwner(access)
        require(page >= 0) { "page_invalid" }
        require(size in 1..100) { "size_invalid" }
        return CareDraftPageResult(
            items = drafts.listByHousehold(access.householdId, page, size).map { it.toResult() },
            total = drafts.countByHousehold(access.householdId),
            page = page,
            size = size,
        )
    }

    override fun correct(command: CorrectCareDraftCommand, access: HouseholdAccess): CareDraftResult {
        requireOwner(access)
        val now = clock.now(access.zoneId).toInstant()
        validateFields(command.fields, access, now)
        return transaction.execute {
            replay(command.requestId, command.draftId, access)?.let { return@execute it.toResult() }
            val current = lock(command.draftId, access)
            requireVersion(current, command.expectedVersion)
            val revised = current.revise(command.fields.normalized(), now)
            val saved = drafts.save(revised)
            drafts.saveAction(action(current, command.requestId, access, CareDraftActionType.CORRECTED, current.status, saved.status, current.version, saved.version, now))
            saved.toResult()
        }
    }

    override fun confirm(command: ConfirmCareDraftCommand, access: HouseholdAccess): CareDraftConfirmationResult {
        requireOwner(access)
        val now = clock.now(access.zoneId).toInstant()
        return transaction.execute {
            val current = lock(command.draftId, access)
            if (current.status == CareDraftStatus.CONFIRMED) {
                val plan = carePlans.get(requireNotNull(current.planId), access)
                return@execute CareDraftConfirmationResult(current.toResult(), plan)
            }
            requireVersion(current, command.expectedVersion)
            if (!now.isBefore(current.expiresAt)) throw ConflictException("Este rascunho expirou; gere um novo")
            if (current.status != CareDraftStatus.READY) throw ConflictException("Revise os campos pendentes antes de confirmar")
            validateFields(current.fields, access, now)
            val fields = current.fields
            val plan = carePlans.create(
                CreateCarePlanCommand(
                    petId = requireNotNull(fields.petId),
                    type = requireNotNull(fields.type),
                    title = requireNotNull(fields.title),
                    instructions = fields.instructions,
                    startAt = requireNotNull(fields.startAt),
                    zoneId = requireNotNull(fields.zoneId),
                    scheduleRule = requireNotNull(fields.scheduleRule),
                    reminderMinutesBefore = fields.reminderMinutesBefore,
                    responsibleTutorId = fields.responsibleTutorId,
                    critical = fields.critical,
                    escalationDelayMinutes = fields.escalationDelayMinutes,
                    escalationTutorId = fields.escalationTutorId,
                    estimatedCostAmount = fields.estimatedCostAmount,
                    estimatedCostCurrency = fields.estimatedCostCurrency,
                    sourceDraftId = current.id.value,
                ),
                access,
            )
            val confirmed = drafts.save(current.confirm(CarePlanId(plan.id), now))
            drafts.saveAction(action(current, command.requestId, access, CareDraftActionType.CONFIRMED, current.status, confirmed.status, current.version, confirmed.version, now))
            CareDraftConfirmationResult(confirmed.toResult(), plan)
        }
    }

    override fun cancel(command: CancelCareDraftCommand, access: HouseholdAccess): CareDraftResult {
        requireOwner(access)
        val now = clock.now(access.zoneId).toInstant()
        return transaction.execute {
            val current = lock(command.draftId, access)
            if (current.status == CareDraftStatus.CANCELLED) return@execute current.toResult()
            replay(command.requestId, command.draftId, access)?.let { return@execute it.toResult() }
            requireVersion(current, command.expectedVersion)
            val cancelled = drafts.save(current.cancel(now))
            drafts.saveAction(action(current, command.requestId, access, CareDraftActionType.CANCELLED, current.status, cancelled.status, current.version, cancelled.version, now))
            cancelled.toResult()
        }
    }

    override fun addFeedback(command: AddCareDraftFeedbackCommand, access: HouseholdAccess) {
        requireOwner(access)
        val now = clock.now(access.zoneId).toInstant()
        transaction.execute {
            val replay = drafts.findActionByRequestId(command.requestId)
            if (replay != null) {
                if (replay.draftId != command.draftId) throw ConflictException("Identificador de requisição já utilizado")
                return@execute
            }
            val current = drafts.findByIdAndHousehold(command.draftId, access.householdId)
                ?: throw NotFoundException("Rascunho não encontrado")
            drafts.saveFeedback(
                AssistantFeedback(
                    draftId = current.id,
                    householdId = current.householdId,
                    actorTutorId = access.actorTutorId,
                    positive = command.positive,
                    correctedFields = command.correctedFields,
                    reason = command.reason,
                    comment = command.comment?.trim()?.takeIf(String::isNotEmpty),
                    createdAt = now,
                ),
            )
            drafts.saveAction(action(current, command.requestId, access, CareDraftActionType.FEEDBACK, current.status, current.status, current.version, current.version, now))
        }
    }

    private fun applyExtraction(
        id: CareDraftId,
        extraction: ExtractedCareDraft,
        access: HouseholdAccess,
        latencyMillis: Long,
    ): CareDraftResult {
        val at = clock.now(access.zoneId).toInstant()
        return transaction.execute {
            val current = lock(id, access)
            val normalized = normalizeExtraction(extraction, access, at)
            val extracted = current.applyExtraction(
                extractedFields = normalized.fields,
                extractedEvidence = normalized.evidence,
                extractedMissing = normalized.missingFields,
                extractedWarnings = normalized.warnings,
                extractedProvenance = normalized.provenance,
                provider = extractor.provider,
                model = extractor.model,
                at = at,
            )
            val saved = drafts.save(extracted)
            drafts.saveAction(action(current, null, access, CareDraftActionType.EXTRACTED, current.status, saved.status, current.version, saved.version, at))
            interactions.save(
                interaction(saved.id, access, latencyMillis, AiInteractionOutcome.SUCCESS, null, at, extraction.inputTokens, extraction.outputTokens),
            )
            saved.toResult()
        }
    }

    private fun failExtraction(
        id: CareDraftId,
        exception: RuntimeException,
        access: HouseholdAccess,
        latencyMillis: Long,
    ): CareDraftResult {
        val at = clock.now(access.zoneId).toInstant()
        val extractionException = exception as? CareInstructionExtractionException
        val code = extractionException?.code ?: "AI_OUTPUT_INVALID"
        val outcome = if (code == "AI_OUTPUT_INVALID") AiInteractionOutcome.INVALID_OUTPUT else AiInteractionOutcome.PROVIDER_ERROR
        return transaction.execute {
            val current = lock(id, access)
            val failed = drafts.save(
                current.fail(
                    code,
                    CareDraftWarning(
                        code = "AI_UNAVAILABLE",
                        message = "Não foi possível interpretar agora. Você pode preencher o formulário manualmente.",
                        blocking = true,
                    ),
                    at,
                ),
            )
            drafts.saveAction(action(current, null, access, CareDraftActionType.FAILED, current.status, failed.status, current.version, failed.version, at))
            interactions.save(interaction(failed.id, access, latencyMillis, outcome, code, at))
            failed.toResult()
        }
    }

    private fun normalizeExtraction(extraction: ExtractedCareDraft, access: HouseholdAccess, now: Instant): ExtractedCareDraft {
        val extracted = extraction.fields
        val validPetId = extracted.petId?.takeIf { pets.existsForHousehold(it, access.householdId) }
        val schedule = when {
            CareDraftField.SCHEDULE in extraction.missingFields -> null
            extracted.scheduleRule != null -> extracted.scheduleRule
            else -> ScheduleRule.oneTime()
        }
        var fields = extracted.copy(
            petId = validPetId,
            zoneId = extracted.zoneId ?: access.zoneId,
            scheduleRule = schedule,
            responsibleTutorId = extracted.responsibleTutorId ?: access.actorTutorId,
            title = extracted.title?.trim(),
            instructions = extracted.instructions?.trim()?.takeIf(String::isNotEmpty),
            estimatedCostCurrency = extracted.estimatedCostCurrency?.trim()?.uppercase(),
        )
        val warnings = extraction.warnings.toMutableList()
        val missing = extraction.missingFields.toMutableSet()
        if (extracted.petId != null && validPetId == null) missing += CareDraftField.PET
        if (fields.startAt?.isBefore(now.minus(Duration.ofMinutes(5))) == true) {
            fields = fields.copy(startAt = null)
            missing += CareDraftField.START_AT
            warnings += CareDraftWarning("START_IN_PAST", "A data informada já passou; escolha uma nova data e hora.", true)
        }
        val provenance = extraction.provenance.toMutableMap().apply {
            if (fields.zoneId != null && CareDraftField.TIMEZONE !in this) this[CareDraftField.TIMEZONE] = CareDraftFieldProvenance.SYSTEM_DEFAULT
            if (fields.responsibleTutorId != null && CareDraftField.RESPONSIBLE !in this) this[CareDraftField.RESPONSIBLE] = CareDraftFieldProvenance.SYSTEM_DEFAULT
            if (CareDraftField.REMINDER !in this) this[CareDraftField.REMINDER] = CareDraftFieldProvenance.SYSTEM_DEFAULT
            if (CareDraftField.CRITICAL !in this) this[CareDraftField.CRITICAL] = CareDraftFieldProvenance.SYSTEM_DEFAULT
            if (schedule != null && extracted.scheduleRule == null && CareDraftField.SCHEDULE !in missing) {
                this[CareDraftField.SCHEDULE] = CareDraftFieldProvenance.SYSTEM_DEFAULT
            }
        }
        return extraction.copy(fields = fields, missingFields = missing + fields.missingRequired(), warnings = warnings, provenance = provenance)
    }

    private fun validateFields(fields: CareDraftFields, access: HouseholdAccess, now: Instant) {
        require(fields.missingRequired().isEmpty()) { "care_draft_required_fields_missing" }
        require(!requireNotNull(fields.startAt).isBefore(now.minus(Duration.ofMinutes(5)))) { "care_plan_start_in_past" }
        require(pets.existsForHousehold(requireNotNull(fields.petId), access.householdId)) { "care_draft_pet_not_found" }
        val responsible = members.findAccess(requireNotNull(fields.responsibleTutorId), access.householdId)
            ?: throw NotFoundException("Responsável não encontrado")
        require(responsible.role != HouseholdRole.VIEWER) { "care_responsible_must_be_caregiver" }
        if (fields.critical) {
            val escalation = members.findAccess(requireNotNull(fields.escalationTutorId), access.householdId)
                ?: throw NotFoundException("Destinatário da escala não encontrado")
            require(escalation.role == HouseholdRole.OWNER) { "care_escalation_target_must_be_owner" }
        }
    }

    private fun CareDraftFields.normalized() = copy(
        title = title?.trim(),
        instructions = instructions?.trim()?.takeIf(String::isNotEmpty),
        estimatedCostCurrency = estimatedCostCurrency?.trim()?.uppercase(),
    )

    private fun replay(requestId: UUID, draftId: CareDraftId, access: HouseholdAccess): CareDraft? {
        val action = drafts.findActionByRequestId(requestId) ?: return null
        if (action.draftId != draftId) throw ConflictException("Identificador de requisição já utilizado")
        return drafts.findByIdAndHousehold(draftId, access.householdId)
            ?: throw NotFoundException("Rascunho não encontrado")
    }

    private fun lock(id: CareDraftId, access: HouseholdAccess) =
        drafts.findByIdAndHouseholdForUpdate(id, access.householdId)
            ?: throw NotFoundException("Rascunho não encontrado")

    private fun requireVersion(draft: CareDraft, expectedVersion: Long) {
        if (draft.version != expectedVersion) throw ConflictException("Este rascunho foi alterado; atualize antes de continuar")
    }

    private fun requireOwner(access: HouseholdAccess) {
        if (!access.can(HouseholdPermission.MANAGE_PLANS)) {
            throw ForbiddenException("Somente proprietários podem criar planos com o assistente")
        }
    }

    private fun action(
        draft: CareDraft,
        requestId: UUID?,
        access: HouseholdAccess,
        type: CareDraftActionType,
        previous: CareDraftStatus?,
        next: CareDraftStatus,
        previousVersion: Long?,
        newVersion: Long?,
        at: Instant,
    ) = CareDraftAction(
        draftId = draft.id,
        requestId = requestId,
        actorTutorId = access.actorTutorId,
        channel = CareDraftChannel.WEB,
        action = type,
        previousStatus = previous,
        newStatus = next,
        previousVersion = previousVersion,
        newVersion = newVersion,
        happenedAt = at,
    )

    private fun interaction(
        draftId: CareDraftId?,
        access: HouseholdAccess,
        latencyMillis: Long,
        outcome: AiInteractionOutcome,
        errorCode: String?,
        at: Instant,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ) = AiInteraction(
        draftId = draftId,
        householdId = access.householdId,
        actorTutorId = access.actorTutorId,
        operation = "CARE_DRAFT_EXTRACTION",
        channel = CareDraftChannel.WEB,
        provider = extractor.provider,
        model = extractor.model,
        promptVersion = extractor.promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        latencyMillis = latencyMillis,
        outcome = outcome,
        errorCode = errorCode,
        createdAt = at,
    )

    private fun CareDraft.toResult(): CareDraftResult {
        val zone = fields.zoneId
        val schedule = fields.scheduleRule
        return CareDraftResult(
            id = id.value,
            version = version,
            channel = channel,
            inputType = inputType,
            status = status,
            fields = CareDraftFieldsResult(
                petId = fields.petId?.value,
                type = fields.type,
                title = fields.title,
                instructions = fields.instructions,
                startAt = fields.startAt,
                startAtLocal = if (zone != null) fields.startAt?.atZone(zone)?.toLocalDateTime() else null,
                timezone = zone?.id,
                scheduleRule = schedule?.let {
                    CareScheduleRuleResult(
                        kind = it.kind,
                        calendarUnit = it.calendarUnit,
                        intervalCount = it.intervalCount,
                        fixedIntervalMinutes = it.fixedInterval?.toMinutes(),
                        dailyTimes = it.dailyTimes.map { time -> time.toString() },
                        repetitions = it.repetitions,
                        endAt = it.endAt,
                    )
                },
                reminderMinutesBefore = fields.reminderMinutesBefore,
                responsibleTutorId = fields.responsibleTutorId?.value,
                critical = fields.critical,
                escalationDelayMinutes = fields.escalationDelayMinutes,
                escalationTutorId = fields.escalationTutorId?.value,
                estimatedCostAmount = fields.estimatedCostAmount,
                estimatedCostCurrency = fields.estimatedCostCurrency,
            ),
            evidence = evidence,
            missingFields = missingFields,
            warnings = warnings,
            provenance = provenance,
            provider = provider,
            model = model,
            promptVersion = promptVersion,
            planId = planId?.value,
            failureCode = failureCode,
            createdAt = createdAt,
            updatedAt = updatedAt,
            expiresAt = expiresAt,
            confirmedAt = confirmedAt,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun elapsedMillis(startedAt: Long) = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(0)

    companion object {
        private const val MAX_PETS_IN_CONTEXT = 100
    }
}
