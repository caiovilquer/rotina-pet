package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceAction
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceActionType
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CarePlan
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanMaterializationStatus
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.usecase.command.CompleteCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.SearchCareOccurrencesQuery
import dev.vilquer.petcarescheduler.usecase.command.UndoCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.UpdateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.AssignCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceActionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceFilter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CarePlanRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CarePlanMaterializationCursorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareEscalationOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareEscalationOutboxMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdActivityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareOccurrenceUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareScheduleMaintenanceUseCase
import dev.vilquer.petcarescheduler.usecase.result.CareOccurrenceResult
import dev.vilquer.petcarescheduler.usecase.result.CareOccurrencesPageResult
import dev.vilquer.petcarescheduler.usecase.result.CarePlanResult
import dev.vilquer.petcarescheduler.usecase.result.CarePlansPageResult
import dev.vilquer.petcarescheduler.usecase.result.CareScheduleRuleResult
import dev.vilquer.petcarescheduler.usecase.result.TodayCareResult
import java.time.Duration
import java.time.Instant

class CareAppService(
    private val plans: CarePlanRepositoryPort,
    private val cursors: CarePlanMaterializationCursorRepositoryPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val actions: CareOccurrenceActionRepositoryPort,
    private val pets: PetRepositoryPort,
    private val tutors: TutorRepositoryPort,
    private val reminderOutbox: CareReminderOutboxPort,
    private val escalationOutbox: CareEscalationOutboxPort,
    private val householdMembers: HouseholdMemberRepositoryPort,
    private val householdActivities: HouseholdActivityRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
) : CarePlanUseCase, CareOccurrenceUseCase, CareScheduleMaintenanceUseCase {

    override fun create(command: CreateCarePlanCommand, access: HouseholdAccess): CarePlanResult {
        requirePermission(access, HouseholdPermission.MANAGE_PLANS)
        command.sourceDraftId?.let { sourceDraftId ->
            plans.findBySourceDraftId(sourceDraftId, access.householdId)?.let { return it.toResult() }
        }
        val pet = pets.findByIdAndHousehold(command.petId, access.householdId)
            ?: throw NotFoundException("Pet não encontrado")
        val responsible = command.responsibleTutorId ?: access.actorTutorId
        requireCaregiver(responsible, access.householdId)
        validateEscalation(command.critical, command.escalationDelayMinutes, command.escalationTutorId, access.householdId)
        val now = clock.now(access.zoneId)
        require(!command.startAt.isBefore(now.toInstant().minus(Duration.ofMinutes(5)))) { "care_plan_start_in_past" }
        val plan = CarePlan(
            sourceDraftId = command.sourceDraftId,
            householdId = access.householdId,
            tutorId = access.actorTutorId,
            petId = command.petId,
            responsibleTutorId = responsible,
            type = command.type,
            title = command.title.trim(),
            instructions = command.instructions?.trim()?.takeIf { it.isNotEmpty() },
            startAt = command.startAt,
            zoneId = command.zoneId,
            scheduleRule = command.scheduleRule,
            reminderMinutesBefore = command.reminderMinutesBefore,
            critical = command.critical,
            escalationDelayMinutes = command.escalationDelayMinutes,
            escalationTutorId = command.escalationTutorId,
            estimatedCostAmount = command.estimatedCostAmount,
            estimatedCostCurrency = command.estimatedCostCurrency?.trim()?.uppercase(),
            createdAt = now.toInstant(),
            updatedAt = now.toInstant(),
        )
        return transaction.execute {
            val saved = plans.save(plan)
            val initial = saved.initialCursor(saved.startAt, now.toInstant())
            val batch = saved.materialize(initial, horizon(now.toInstant()), now.toInstant())
            occurrences.saveAllIfAbsent(batch.occurrences)
            cursors.save(batch.cursor)
            saved.toResult()
        }
    }

    override fun update(command: UpdateCarePlanCommand, access: HouseholdAccess): CarePlanResult = transaction.execute {
        requirePermission(access, HouseholdPermission.MANAGE_PLANS)
        val existing = plans.findByIdAndHouseholdForUpdate(command.planId, access.householdId)
            ?: throw NotFoundException("Plano de cuidado não encontrado")
        if (!existing.active) throw ConflictException("Este plano já foi encerrado")
        val now = clock.now(access.zoneId)
        val responsible = command.responsibleTutorId ?: existing.responsibleTutorId
        requireCaregiver(responsible, access.householdId)
        validateEscalation(command.critical, command.escalationDelayMinutes, command.escalationTutorId, access.householdId)
        val scheduleChanged = existing.startAt != command.startAt || existing.zoneId != command.zoneId ||
            existing.scheduleRule != command.scheduleRule
        val lockedCursor = cursors.findForUpdate(existing.id, existing.scheduleRevision)
        val updated = existing.copy(
            scheduleRevision = if (scheduleChanged) existing.scheduleRevision + 1 else existing.scheduleRevision,
            type = command.type,
            title = command.title.trim(),
            instructions = command.instructions?.trim()?.takeIf { it.isNotEmpty() },
            startAt = command.startAt,
            zoneId = command.zoneId,
            scheduleRule = command.scheduleRule,
            reminderMinutesBefore = command.reminderMinutesBefore,
            responsibleTutorId = responsible,
            critical = command.critical,
            escalationDelayMinutes = command.escalationDelayMinutes,
            escalationTutorId = command.escalationTutorId,
            estimatedCostAmount = command.estimatedCostAmount,
            estimatedCostCurrency = command.estimatedCostCurrency?.trim()?.uppercase(),
            updatedAt = now.toInstant(),
        )
        reminderOutbox.cancelPendingForPlan(existing.id, now.toInstant(), now.toInstant())
        escalationOutbox.cancelPendingForPlan(existing.id, now.toInstant(), now.toInstant())

        if (scheduleChanged) {
            lockedCursor?.let { cursors.save(it.supersede(now.toInstant())) }
            occurrences.cancelScheduledFrom(existing.id, now.toInstant(), now.toInstant())
            val saved = plans.save(updated)
            val initial = saved.initialCursor(now.toInstant(), now.toInstant())
            val batch = saved.materialize(initial, horizon(now.toInstant()), now.toInstant())
            occurrences.saveAllIfAbsent(batch.occurrences)
            cursors.save(batch.cursor)
            saved.toResult()
        } else {
            val saved = plans.save(updated)
            occurrences.findScheduledFrom(saved.id, now.toInstant()).forEach { occurrence ->
                occurrences.save(occurrence.copy(
                    responsibleTutorId = saved.responsibleTutorId,
                    type = saved.type,
                    title = saved.title,
                    instructions = saved.instructions,
                    critical = saved.critical,
                    escalationDelayMinutes = saved.escalationDelayMinutes,
                    escalationTutorId = saved.escalationTutorId,
                    estimatedCostAmount = saved.estimatedCostAmount,
                    estimatedCostCurrency = saved.estimatedCostCurrency,
                    updatedAt = now.toInstant(),
                ))
            }
            saved.toResult()
        }
    }

    override fun deactivate(planId: CarePlanId, access: HouseholdAccess) {
        requirePermission(access, HouseholdPermission.MANAGE_PLANS)
        transaction.execute {
            val plan = plans.findByIdAndHouseholdForUpdate(planId, access.householdId)
                ?: throw NotFoundException("Plano de cuidado não encontrado")
            if (!plan.active) return@execute
            val now = clock.now().toInstant()
            cursors.findForUpdate(plan.id, plan.scheduleRevision)?.let { cursors.save(it.supersede(now)) }
            reminderOutbox.cancelPendingForPlan(plan.id, now, now)
            escalationOutbox.cancelPendingForPlan(plan.id, now, now)
            occurrences.cancelAllScheduled(plan.id, now)
            plans.save(plan.deactivate(now))
        }
    }

    override fun get(planId: CarePlanId, access: HouseholdAccess): CarePlanResult {
        requirePermission(access, HouseholdPermission.VIEW)
        return plans.findByIdAndHousehold(planId, access.householdId)?.toResult()
            ?: throw NotFoundException("Plano de cuidado não encontrado")
    }

    override fun list(access: HouseholdAccess, petId: dev.vilquer.petcarescheduler.core.domain.entity.PetId?, active: Boolean?, page: Int, size: Int): CarePlansPageResult {
        requirePermission(access, HouseholdPermission.VIEW)
        validatePage(page, size)
        petId?.let { require(pets.existsForHousehold(it, access.householdId)) { "care_plan_pet_not_found" } }
        return CarePlansPageResult(
            plans.listByHousehold(access.householdId, petId, active, page, size).map { it.toResult() },
            plans.countByHousehold(access.householdId, petId, active),
            page,
            size,
        )
    }

    override fun search(query: SearchCareOccurrencesQuery, access: HouseholdAccess): CareOccurrencesPageResult {
        requirePermission(access, HouseholdPermission.VIEW)
        validatePage(query.page, query.size)
        require(query.to.isAfter(query.from)) { "care_period_invalid" }
        require(Duration.between(query.from, query.to).toDays() <= MAX_SEARCH_DAYS) { "care_period_too_large" }
        query.petId?.let { require(pets.existsForHousehold(it, access.householdId)) { "care_occurrence_pet_not_found" } }
        val filter = CareOccurrenceFilter(query.from, query.to, query.petId, query.type, query.status)
        return CareOccurrencesPageResult(
            occurrences.searchByHousehold(access.householdId, filter, query.page, query.size).map { it.toResult() },
            occurrences.countByHousehold(access.householdId, filter),
            query.page,
            query.size,
        )
    }

    override fun today(access: HouseholdAccess): TodayCareResult {
        requirePermission(access, HouseholdPermission.VIEW)
        val now = clock.now(access.zoneId)
        val start = now.toLocalDate().atStartOfDay(access.zoneId).toInstant()
        val end = now.toLocalDate().plusDays(1).atStartOfDay(access.zoneId).toInstant()
        val overdueFilter = CareOccurrenceFilter(start.minus(Duration.ofDays(366 * 2L)), start, status = CareOccurrenceStatus.SCHEDULED)
        val todayFilter = CareOccurrenceFilter(start, end)
        val overdue = occurrences.searchByHousehold(access.householdId, overdueFilter, 0, TODAY_LIMIT).map { it.toResult() }
        val today = occurrences.searchByHousehold(access.householdId, todayFilter, 0, TODAY_LIMIT)
        val scheduled = today.filter { it.status == CareOccurrenceStatus.SCHEDULED }.map { it.toResult() }
        val completed = today.filter { it.status == CareOccurrenceStatus.COMPLETED }.map { it.toResult() }
        val nextWeek = CareOccurrenceFilter(start, start.plus(Duration.ofDays(7)), status = CareOccurrenceStatus.SCHEDULED)
        return TodayCareResult(now.toLocalDate(), overdue, scheduled, completed, occurrences.countByHousehold(access.householdId, nextWeek), access.zoneId.id)
    }

    override fun complete(command: CompleteCareOccurrenceCommand, access: HouseholdAccess): CareOccurrenceResult = transaction.execute {
        requirePermission(access, HouseholdPermission.COMPLETE_CARE)
        val tutorId = access.actorTutorId
        replay(command.requestId, command.occurrenceId.value, access, CareOccurrenceActionType.COMPLETE)?.let {
            return@execute it
        }
        require(command.note == null || command.note.length <= 500) { "care_occurrence_note_invalid" }
        val occurrence = lockCurrentOccurrence(command.occurrenceId, access.householdId)
        // Uma segunda requisição com a mesma chave pode ter ficado esperando o
        // lock da ocorrência. Revalidar depois do lock mantém a resposta
        // idempotente também sob concorrência real entre instâncias.
        replay(command.requestId, command.occurrenceId.value, access, CareOccurrenceActionType.COMPLETE)?.let {
            return@execute it
        }
        if (occurrence.status == CareOccurrenceStatus.COMPLETED) {
            throw ConflictException("Este cuidado já foi concluído; a agenda foi atualizada para evitar registro em dobro")
        }
        if (occurrence.status != CareOccurrenceStatus.SCHEDULED) {
            throw ConflictException("Este cuidado não está mais disponível para conclusão")
        }
        val now = clock.now().toInstant()
        val completed = occurrences.save(occurrence.complete(tutorId, now, command.note))
        actions.save(
            CareOccurrenceAction(
                requestId = command.requestId,
                occurrenceId = occurrence.id,
                actorTutorId = tutorId,
                action = CareOccurrenceActionType.COMPLETE,
                previousStatus = occurrence.status,
                newStatus = completed.status,
                happenedAt = now,
            ),
        )
        householdActivities.save(HouseholdActivity(
            householdId = access.householdId, type = HouseholdActivityType.CARE_COMPLETED,
            actorTutorId = tutorId, petId = completed.petId, careOccurrenceId = completed.id.value,
            summary = "${completed.title} foi concluído", happenedAt = now,
        ))
        completed.toResult()
    }

    override fun undo(command: UndoCareOccurrenceCommand, access: HouseholdAccess): CareOccurrenceResult = transaction.execute {
        requirePermission(access, HouseholdPermission.COMPLETE_CARE)
        val tutorId = access.actorTutorId
        replay(command.requestId, command.occurrenceId.value, access, CareOccurrenceActionType.UNDO)?.let {
            return@execute it
        }
        val occurrence = lockCurrentOccurrence(command.occurrenceId, access.householdId)
        replay(command.requestId, command.occurrenceId.value, access, CareOccurrenceActionType.UNDO)?.let {
            return@execute it
        }
        if (occurrence.status != CareOccurrenceStatus.COMPLETED) {
            throw ConflictException("Este cuidado não está concluído")
        }
        val now = clock.now().toInstant()
        val reopened = try {
            occurrence.reopen(tutorId, now, UNDO_WINDOW)
        } catch (ex: IllegalArgumentException) {
            throw ConflictException("O prazo seguro para desfazer esta conclusão terminou")
        }
        occurrences.save(reopened)
        actions.save(
            CareOccurrenceAction(
                requestId = command.requestId,
                occurrenceId = occurrence.id,
                actorTutorId = tutorId,
                action = CareOccurrenceActionType.UNDO,
                previousStatus = occurrence.status,
                newStatus = reopened.status,
                happenedAt = now,
            ),
        )
        householdActivities.save(HouseholdActivity(
            householdId = access.householdId, type = HouseholdActivityType.CARE_REOPENED,
            actorTutorId = tutorId, petId = reopened.petId, careOccurrenceId = reopened.id.value,
            summary = "${reopened.title} foi reaberto", happenedAt = now,
        ))
        reopened.toResult()
    }

    override fun assign(command: AssignCareOccurrenceCommand, access: HouseholdAccess): CareOccurrenceResult = transaction.execute {
        requirePermission(access, HouseholdPermission.MANAGE_PLANS)
        requireCaregiver(command.responsibleTutorId, access.householdId)
        val occurrence = lockCurrentOccurrence(command.occurrenceId, access.householdId)
        if (occurrence.version != command.expectedVersion) throw ConflictException("O cuidado foi alterado. Atualize e tente novamente")
        if (occurrence.status != CareOccurrenceStatus.SCHEDULED) throw ConflictException("Só cuidados pendentes podem ser atribuídos")
        val now = clock.now().toInstant()
        val saved = occurrences.save(occurrence.assignTo(command.responsibleTutorId, now))
        reminderOutbox.cancelPendingForPlan(saved.planId, saved.dueAt, now)
        householdActivities.save(HouseholdActivity(
            householdId = access.householdId, type = HouseholdActivityType.CARE_ASSIGNED,
            actorTutorId = access.actorTutorId, targetTutorId = command.responsibleTutorId,
            petId = saved.petId, careOccurrenceId = saved.id.value,
            summary = "Responsável por ${saved.title} foi atualizado", happenedAt = now,
        ))
        saved.toResult()
    }

    override fun materializeAndEnqueueReminders() {
        val defaultNow = clock.now().toInstant()
        var page = 0
        while (true) {
            val batch = plans.findActive(page, MATERIALIZATION_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { plan ->
                transaction.execute {
                    val locked = plans.findByIdAndHouseholdForUpdate(plan.id, plan.householdId) ?: return@execute
                    if (!locked.active) return@execute
                    var cursor = cursors.findForUpdate(locked.id, locked.scheduleRevision)
                        ?: locked.initialCursor(defaultNow, defaultNow)
                    for (ignored in 0 until MAX_MATERIALIZATION_BATCHES_PER_PLAN) {
                        val materialized = locked.materialize(cursor, horizon(defaultNow), defaultNow)
                        if (materialized.occurrences.isEmpty()) break
                        occurrences.saveAllIfAbsent(materialized.occurrences)
                        cursor = cursors.save(materialized.cursor)
                        if (cursor.status != CarePlanMaterializationStatus.ACTIVE || cursor.nextDueAt!!.isAfter(horizon(defaultNow))) {
                            break
                        }
                    }
                }
            }
            if (batch.size < MATERIALIZATION_BATCH) break
            page += 1
        }

        occurrences.findReminderCandidates(defaultNow.minus(Duration.ofDays(2)), defaultNow.plus(Duration.ofDays(9)), REMINDER_SCAN_LIMIT)
            .forEach { occurrence ->
                val plan = plans.findByIdAndHousehold(occurrence.planId, occurrence.householdId) ?: return@forEach
                if (!plan.active || plan.scheduleRevision != occurrence.scheduleRevision) return@forEach
                val triggerAt = occurrence.dueAt.minus(Duration.ofMinutes(plan.reminderMinutesBefore.toLong()))
                if (triggerAt.isAfter(defaultNow)) return@forEach
                val member = householdMembers.findAccess(occurrence.responsibleTutorId, occurrence.householdId) ?: return@forEach
                if (member.role == HouseholdRole.VIEWER) return@forEach
                val tutor = tutors.findById(occurrence.responsibleTutorId) ?: return@forEach
                val pet = pets.findById(occurrence.petId) ?: return@forEach
                reminderOutbox.enqueueIfAbsent(
                    CareReminderOutboxMessage(
                        occurrenceId = occurrence.id,
                        tutorId = occurrence.responsibleTutorId,
                        tutorEmail = tutor.email.value,
                        petName = pet.name,
                        createdAt = defaultNow,
                    ),
                )
            }

        occurrences.findCriticalEscalationCandidates(defaultNow.plus(Duration.ofDays(1)), ESCALATION_SCAN_LIMIT).forEach { occurrence ->
            val delay = occurrence.escalationDelayMinutes ?: return@forEach
            val recipientId = occurrence.escalationTutorId ?: return@forEach
            if (occurrence.dueAt.plus(Duration.ofMinutes(delay.toLong())).isAfter(defaultNow)) return@forEach
            val recipient = tutors.findById(recipientId) ?: return@forEach
            val pet = pets.findById(occurrence.petId) ?: return@forEach
            escalationOutbox.enqueueIfAbsent(CareEscalationOutboxMessage(
                occurrenceId = occurrence.id, householdId = occurrence.householdId,
                recipientTutorId = recipientId, recipientEmail = recipient.email.value,
                petName = pet.name, careTitle = occurrence.title, dueAt = occurrence.dueAt,
                createdAt = defaultNow,
            ))
        }
    }

    private fun replay(requestId: java.util.UUID, occurrenceId: java.util.UUID, access: HouseholdAccess, expected: CareOccurrenceActionType): CareOccurrenceResult? {
        val action = actions.findByRequestId(requestId) ?: return null
        if (action.occurrenceId.value != occurrenceId || action.actorTutorId != access.actorTutorId || action.action != expected) {
            throw ConflictException("A chave de idempotência já foi usada em outra operação")
        }
        return occurrences.findByIdAndHousehold(action.occurrenceId, access.householdId)?.toResult()
            ?: throw NotFoundException("Cuidado não encontrado")
    }

    private fun CarePlan.toResult() = CarePlanResult(
        id.value, version, petId.value, responsibleTutorId.value, type, title, instructions,
        startAt, startAt.atZone(zoneId).toLocalDateTime(), CareScheduleRuleResult(
            scheduleRule.kind, scheduleRule.calendarUnit, scheduleRule.intervalCount,
            scheduleRule.fixedInterval?.toMinutes(), scheduleRule.dailyTimes.map { it.toString() },
            scheduleRule.repetitions, scheduleRule.endAt,
        ), reminderMinutesBefore,
        critical, escalationDelayMinutes, escalationTutorId?.value,
        estimatedCostAmount, estimatedCostCurrency, active, zoneId.id,
    )

    private fun CareOccurrence.toResult(): CareOccurrenceResult {
        val undoUntil = completedAt?.plus(UNDO_WINDOW)
        return CareOccurrenceResult(
            id.value, version, planId.value, petId.value, responsibleTutorId.value, type, title, instructions,
            dueAt, dueAt.atZone(zoneId).toLocalDateTime(), status,
            completedAt, completedByTutorId?.value, completionNote, critical, escalationDelayMinutes, escalationTutorId?.value,
            estimatedCostAmount, estimatedCostCurrency, undoUntil, zoneId.id,
        )
    }

    private fun lockCurrentOccurrence(occurrenceId: CareOccurrenceId, householdId: HouseholdId): CareOccurrence {
        val planId = occurrences.findPlanIdByIdAndHousehold(occurrenceId, householdId)
            ?: throw NotFoundException("Cuidado não encontrado")
        val plan = plans.findByIdAndHouseholdForUpdate(planId, householdId)
            ?: throw NotFoundException("Plano de cuidado não encontrado")
        val occurrence = occurrences.findByIdAndHouseholdForUpdate(occurrenceId, householdId)
            ?: throw NotFoundException("Cuidado não encontrado")
        if (!plan.active || plan.scheduleRevision != occurrence.scheduleRevision) {
            throw ConflictException("A agenda foi atualizada; consulte os cuidados atuais")
        }
        return occurrence
    }

    private fun requirePermission(access: HouseholdAccess, permission: HouseholdPermission) {
        if (!access.can(permission)) throw dev.vilquer.petcarescheduler.application.exception.ForbiddenException("Seu papel nesta família não permite esta ação")
    }

    private fun requireCaregiver(tutorId: TutorId, householdId: HouseholdId) {
        val member = householdMembers.findAccess(tutorId, householdId) ?: throw NotFoundException("Responsável não encontrado")
        require(member.role != HouseholdRole.VIEWER) { "care_responsible_must_be_caregiver" }
    }

    private fun validateEscalation(critical: Boolean, delay: Int?, target: TutorId?, householdId: HouseholdId) {
        if (!critical) {
            require(delay == null && target == null) { "care_escalation_only_for_critical" }
            return
        }
        require(delay != null && delay in 15..10_080 && target != null) { "care_escalation_invalid" }
        val member = householdMembers.findAccess(target, householdId) ?: throw NotFoundException("Destinatário da escala não encontrado")
        require(member.role == HouseholdRole.OWNER) { "care_escalation_target_must_be_owner" }
    }

    private fun validatePage(page: Int, size: Int) {
        require(page >= 0) { "page_invalid" }
        require(size in 1..100) { "size_invalid" }
    }

    private fun horizon(now: Instant) = now.plus(Duration.ofDays(MATERIALIZATION_HORIZON_DAYS))

    companion object {
        val UNDO_WINDOW: Duration = Duration.ofMinutes(10)
        private const val MATERIALIZATION_HORIZON_DAYS = 90L
        private const val MATERIALIZATION_BATCH = 100
        private const val MAX_MATERIALIZATION_BATCHES_PER_PLAN = 10
        private const val TODAY_LIMIT = 100
        private const val MAX_SEARCH_DAYS = 366L
        private const val REMINDER_SCAN_LIMIT = 500
        private const val ESCALATION_SCAN_LIMIT = 500
    }
}
