package dev.vilquer.petcarescheduler.usecase.result

import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFieldProvenance
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftInputType
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftStatus
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftWarning
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class CareDraftFieldsResult(
    val petId: Long?,
    val type: EventType?,
    val title: String?,
    val instructions: String?,
    val startAt: Instant?,
    val startAtLocal: LocalDateTime?,
    val timezone: String?,
    val scheduleRule: CareScheduleRuleResult?,
    val reminderMinutesBefore: Int,
    val responsibleTutorId: Long?,
    val critical: Boolean,
    val escalationDelayMinutes: Int?,
    val escalationTutorId: Long?,
    val estimatedCostAmount: BigDecimal?,
    val estimatedCostCurrency: String?,
)

data class CareDraftResult(
    val id: UUID,
    val version: Long?,
    val channel: CareDraftChannel,
    val inputType: CareDraftInputType,
    val status: CareDraftStatus,
    val fields: CareDraftFieldsResult,
    val evidence: Map<CareDraftField, String>,
    val missingFields: Set<CareDraftField>,
    val warnings: List<CareDraftWarning>,
    val provenance: Map<CareDraftField, CareDraftFieldProvenance>,
    val provider: String?,
    val model: String?,
    val promptVersion: String,
    val planId: UUID?,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val confirmedAt: Instant?,
)

data class CareDraftPageResult(
    val items: List<CareDraftResult>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class CareDraftConfirmationResult(
    val draft: CareDraftResult,
    val plan: CarePlanResult,
)
