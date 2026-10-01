package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceAction
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlan
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanMaterializationCursor
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareOccurrenceActionJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareOccurrenceJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CarePlanJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CarePlanMaterializationCursorJpa
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

fun CarePlanJpa.toDomain() = CarePlan(
    id = CarePlanId(id), version = version, sourceDraftId = sourceDraftId, scheduleRevision = scheduleRevision, householdId = HouseholdId(householdId), tutorId = TutorId(tutorId), petId = PetId(petId),
    responsibleTutorId = TutorId(responsibleTutorId), type = type, title = title,
    instructions = instructions, startAt = startAt, zoneId = ZoneId.of(zoneId),
    scheduleRule = ScheduleRule(
        kind = scheduleKind, calendarUnit = calendarUnit, intervalCount = intervalCount,
        fixedInterval = fixedIntervalSeconds?.let(Duration::ofSeconds),
        dailyTimes = dailyTimes?.split(',')?.filter(String::isNotBlank)?.map(LocalTime::parse) ?: emptyList(),
        repetitions = repetitions, endAt = endAt,
    ),
    reminderMinutesBefore = reminderMinutesBefore, critical = critical, escalationDelayMinutes = escalationDelayMinutes,
    escalationTutorId = escalationTutorId?.let(::TutorId), estimatedCostAmount = estimatedCostAmount,
    estimatedCostCurrency = estimatedCostCurrency, active = active, createdAt = createdAt, updatedAt = updatedAt,
)

fun CarePlan.toJpa() = CarePlanJpa().also {
    it.id = id.value; it.version = version; it.sourceDraftId = sourceDraftId; it.scheduleRevision = scheduleRevision; it.householdId = householdId.value; it.tutorId = tutorId.value; it.petId = petId.value
    it.responsibleTutorId = responsibleTutorId.value; it.type = type; it.title = title
    it.instructions = instructions; it.startAt = startAt; it.zoneId = zoneId.id; it.scheduleKind = scheduleRule.kind
    it.calendarUnit = scheduleRule.calendarUnit; it.intervalCount = scheduleRule.intervalCount
    it.fixedIntervalSeconds = scheduleRule.fixedInterval?.seconds
    it.dailyTimes = scheduleRule.dailyTimes.takeIf { times -> times.isNotEmpty() }?.joinToString(",")
    it.repetitions = scheduleRule.repetitions; it.endAt = scheduleRule.endAt
    it.reminderMinutesBefore = reminderMinutesBefore; it.critical = critical; it.escalationDelayMinutes = escalationDelayMinutes
    it.escalationTutorId = escalationTutorId?.value; it.estimatedCostAmount = estimatedCostAmount
    it.estimatedCostCurrency = estimatedCostCurrency; it.active = active; it.createdAt = createdAt; it.updatedAt = updatedAt
}

fun CareOccurrenceJpa.toDomain() = CareOccurrence(
    id = CareOccurrenceId(id), version = version, planId = CarePlanId(planId), scheduleRevision = scheduleRevision,
    householdId = HouseholdId(householdId), tutorId = TutorId(tutorId), petId = PetId(petId), responsibleTutorId = TutorId(responsibleTutorId),
    sequence = sequence, type = type, title = title, instructions = instructions,
    dueAt = dueAt, zoneId = ZoneId.of(zoneId), status = status, completedAt = completedAt,
    completedByTutorId = completedByTutorId?.let(::TutorId), completionNote = completionNote, critical = critical,
    escalationDelayMinutes = escalationDelayMinutes, escalationTutorId = escalationTutorId?.let(::TutorId),
    estimatedCostAmount = estimatedCostAmount, estimatedCostCurrency = estimatedCostCurrency,
    createdAt = createdAt, updatedAt = updatedAt, legacyEventId = legacyEventId,
)

fun CareOccurrence.toJpa() = CareOccurrenceJpa().also {
    it.id = id.value; it.version = version; it.planId = planId.value; it.scheduleRevision = scheduleRevision; it.householdId = householdId.value
    it.tutorId = tutorId.value; it.petId = petId.value; it.responsibleTutorId = responsibleTutorId.value
    it.sequence = sequence; it.type = type; it.title = title; it.instructions = instructions; it.dueAt = dueAt; it.zoneId = zoneId.id
    it.status = status; it.completedAt = completedAt; it.completedByTutorId = completedByTutorId?.value
    it.completionNote = completionNote; it.critical = critical; it.escalationDelayMinutes = escalationDelayMinutes
    it.escalationTutorId = escalationTutorId?.value; it.estimatedCostAmount = estimatedCostAmount
    it.estimatedCostCurrency = estimatedCostCurrency; it.createdAt = createdAt; it.updatedAt = updatedAt; it.legacyEventId = legacyEventId
}

fun CarePlanMaterializationCursorJpa.toDomain() = CarePlanMaterializationCursor(
    planId = CarePlanId(planId), scheduleRevision = scheduleRevision, nextSequence = nextSequence,
    nextDueAt = nextDueAt, materializedThrough = materializedThrough, status = status,
    version = version, updatedAt = updatedAt,
)

fun CarePlanMaterializationCursor.toJpa() = CarePlanMaterializationCursorJpa().also {
    it.planId = planId.value; it.scheduleRevision = scheduleRevision; it.nextSequence = nextSequence
    it.nextDueAt = nextDueAt; it.materializedThrough = materializedThrough; it.status = status
    it.version = version; it.updatedAt = updatedAt
}

fun CareOccurrenceActionJpa.toDomain() = CareOccurrenceAction(
    id = id, requestId = requestId, occurrenceId = CareOccurrenceId(occurrenceId), actorTutorId = TutorId(actorTutorId),
    action = action, previousStatus = previousStatus, newStatus = newStatus, happenedAt = happenedAt,
)

fun CareOccurrenceAction.toJpa() = CareOccurrenceActionJpa().also {
    it.id = id; it.requestId = requestId; it.occurrenceId = occurrenceId.value; it.actorTutorId = actorTutorId.value
    it.action = action; it.previousStatus = previousStatus; it.newStatus = newStatus; it.happenedAt = happenedAt
}
