package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFieldProvenance
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFields
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftWarning
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleKind
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Component
class CareDraftJsonCodec {
    private val json = jacksonObjectMapper()

    fun fields(value: CareDraftFields): JsonNode = json.valueToTree(value.toPayload())
    fun fields(value: JsonNode): CareDraftFields = json.treeToValue(value, CareDraftPayloadJson::class.java).toDomain()

    fun evidence(value: Map<CareDraftField, String>): JsonNode =
        json.valueToTree(value.mapKeys { it.key.name })

    fun evidence(value: JsonNode): Map<CareDraftField, String> =
        json.readValue<Map<String, String>>(value.toString()).mapKeys { CareDraftField.valueOf(it.key) }

    fun missing(value: Set<CareDraftField>): JsonNode = json.valueToTree(value.map(CareDraftField::name))
    fun missing(value: JsonNode): Set<CareDraftField> = json.readValue<List<String>>(value.toString()).mapTo(linkedSetOf(), CareDraftField::valueOf)

    fun warnings(value: List<CareDraftWarning>): JsonNode = json.valueToTree(value)
    fun warnings(value: JsonNode): List<CareDraftWarning> = json.readValue(value.toString())

    fun provenance(value: Map<CareDraftField, CareDraftFieldProvenance>): JsonNode =
        json.valueToTree(value.mapKeys { it.key.name }.mapValues { it.value.name })

    fun provenance(value: JsonNode): Map<CareDraftField, CareDraftFieldProvenance> =
        json.readValue<Map<String, String>>(value.toString()).mapKeys { CareDraftField.valueOf(it.key) }
            .mapValues { CareDraftFieldProvenance.valueOf(it.value) }

    fun fieldSet(value: Set<CareDraftField>): JsonNode = json.valueToTree(value.map(CareDraftField::name))

    private data class CareDraftPayloadJson(
        val petId: Long? = null,
        val type: EventType? = null,
        val title: String? = null,
        val instructions: String? = null,
        val startAt: String? = null,
        val zoneId: String? = null,
        val scheduleKind: ScheduleKind? = null,
        val calendarUnit: CalendarIntervalUnit? = null,
        val intervalCount: Long? = null,
        val fixedIntervalSeconds: Long? = null,
        val dailyTimes: List<String> = emptyList(),
        val repetitions: Long? = null,
        val endAt: String? = null,
        val reminderMinutesBefore: Int = 0,
        val responsibleTutorId: Long? = null,
        val critical: Boolean = false,
        val escalationDelayMinutes: Int? = null,
        val escalationTutorId: Long? = null,
        val estimatedCostAmount: BigDecimal? = null,
        val estimatedCostCurrency: String? = null,
    )

    private fun CareDraftFields.toPayload() = CareDraftPayloadJson(
        petId = petId?.value,
        type = type,
        title = title,
        instructions = instructions,
        startAt = startAt?.toString(),
        zoneId = zoneId?.id,
        scheduleKind = scheduleRule?.kind,
        calendarUnit = scheduleRule?.calendarUnit,
        intervalCount = scheduleRule?.intervalCount,
        fixedIntervalSeconds = scheduleRule?.fixedInterval?.seconds,
        dailyTimes = scheduleRule?.dailyTimes?.map(LocalTime::toString) ?: emptyList(),
        repetitions = scheduleRule?.repetitions,
        endAt = scheduleRule?.endAt?.toString(),
        reminderMinutesBefore = reminderMinutesBefore,
        responsibleTutorId = responsibleTutorId?.value,
        critical = critical,
        escalationDelayMinutes = escalationDelayMinutes,
        escalationTutorId = escalationTutorId?.value,
        estimatedCostAmount = estimatedCostAmount,
        estimatedCostCurrency = estimatedCostCurrency,
    )

    private fun CareDraftPayloadJson.toDomain() = CareDraftFields(
        petId = petId?.let(::PetId),
        type = type,
        title = title,
        instructions = instructions,
        startAt = startAt?.let(Instant::parse),
        zoneId = zoneId?.let(ZoneId::of),
        scheduleRule = scheduleKind?.let {
            ScheduleRule(
                kind = it,
                calendarUnit = calendarUnit,
                intervalCount = intervalCount,
                fixedInterval = fixedIntervalSeconds?.let(Duration::ofSeconds),
                dailyTimes = dailyTimes.map(LocalTime::parse),
                repetitions = repetitions,
                endAt = endAt?.let(Instant::parse),
            )
        },
        reminderMinutesBefore = reminderMinutesBefore,
        responsibleTutorId = responsibleTutorId?.let(::TutorId),
        critical = critical,
        escalationDelayMinutes = escalationDelayMinutes,
        escalationTutorId = escalationTutorId?.let(::TutorId),
        estimatedCostAmount = estimatedCostAmount,
        estimatedCostCurrency = estimatedCostCurrency,
    )
}
