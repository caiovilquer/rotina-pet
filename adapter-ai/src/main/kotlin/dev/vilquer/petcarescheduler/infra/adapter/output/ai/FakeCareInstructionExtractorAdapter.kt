package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFieldProvenance
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFields
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftWarning
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedCareDraft
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Provider determinístico para desenvolvimento e CI. Ele oferece o mesmo
 * contrato estreito de um provider remoto, sem rede, credenciais ou escrita no
 * domínio. A extração deliberadamente se abstém quando o texto é ambíguo.
 */
class FakeCareInstructionExtractorAdapter(
    private val meterRegistry: MeterRegistry,
) : CareInstructionExtractorPort {
    override val provider = "local-fake"
    override val model = "deterministic-pt-v1"
    override val promptVersion = "care-draft-v1"

    override fun extract(request: CareInstructionExtractionRequest): ExtractedCareDraft {
        val sample = Timer.start(meterRegistry)
        return try {
            parse(request).also {
                meterRegistry.counter("rotinapet.ai.requests", "operation", "care_draft", "outcome", "success").increment()
            }
        } catch (exception: RuntimeException) {
            meterRegistry.counter("rotinapet.ai.requests", "operation", "care_draft", "outcome", "error").increment()
            throw exception
        } finally {
            sample.stop(meterRegistry.timer("rotinapet.ai.latency", "operation", "care_draft", "provider", provider))
        }
    }

    private fun parse(request: CareInstructionExtractionRequest): ExtractedCareDraft {
        val original = request.instruction.trim()
        val normalized = normalize(original)
        val evidence = linkedMapOf<CareDraftField, String>()
        val provenance = linkedMapOf<CareDraftField, CareDraftFieldProvenance>()
        val missing = linkedSetOf<CareDraftField>()
        val warnings = mutableListOf<CareDraftWarning>()

        val matchingPets = request.pets.filter { normalized.contains(normalize(it.name)) }
        val petId = matchingPets.singleOrNull()?.id
        when {
            matchingPets.size == 1 -> explicit(CareDraftField.PET, matchingPets.single().name, evidence, provenance)
            matchingPets.size > 1 -> warnings += CareDraftWarning("PET_AMBIGUOUS", "Mais de um pet corresponde ao nome informado.", true)
            else -> missing += CareDraftField.PET
        }

        val type = extractType(normalized)
        if (type == null) {
            missing += CareDraftField.TYPE
            missing += CareDraftField.TITLE
        } else {
            val typeEvidence = typeKeywords.getValue(type).first { normalized.contains(it) }
            explicit(CareDraftField.TYPE, typeEvidence, evidence, provenance)
            normalized(CareDraftField.TITLE, typeEvidence, evidence, provenance)
        }

        val date = extractDate(normalized, request.receivedAt, request.zoneId)
        val times = extractTimes(normalized)
        val startAt = if (date != null && times.isNotEmpty()) {
            ScheduleRule.resolveLocal(date.atTime(times.first()), request.zoneId).also {
                explicit(CareDraftField.START_AT, "${date.format(DateTimeFormatter.ISO_DATE)} ${times.first()}", evidence, provenance)
            }
        } else {
            missing += CareDraftField.START_AT
            null
        }

        val scheduleExtraction = extractSchedule(normalized, times, startAt, request.zoneId)
        if (scheduleExtraction.ambiguous) {
            missing += CareDraftField.SCHEDULE
            warnings += CareDraftWarning(
                "SCHEDULE_AMBIGUOUS",
                "Informe os horários exatos; frequência por dia não define o intervalo entre as doses.",
                true,
            )
        } else {
            scheduleExtraction.rule?.let {
                provenance[CareDraftField.SCHEDULE] = scheduleExtraction.provenance
                evidence[CareDraftField.SCHEDULE] = scheduleExtraction.evidence
            } ?: run { missing += CareDraftField.SCHEDULE }
        }

        if (clinicalRequest.containsMatchIn(normalized)) {
            warnings += CareDraftWarning(
                "CLINICAL_REQUEST",
                "O RotinaPet organiza informações, mas não realiza diagnóstico nem recomenda tratamento ou dose.",
            )
        }

        val title = type?.let(typeTitles::getValue)
        val fields = CareDraftFields(
            petId = petId,
            type = type,
            title = title,
            startAt = startAt,
            zoneId = request.zoneId,
            scheduleRule = scheduleExtraction.rule,
        )
        provenance[CareDraftField.TIMEZONE] = CareDraftFieldProvenance.SYSTEM_DEFAULT
        evidence[CareDraftField.TIMEZONE] = request.zoneId.id

        return ExtractedCareDraft(
            fields = fields,
            evidence = evidence,
            missingFields = (missing + fields.missingRequired()) - setOf(CareDraftField.TIMEZONE, CareDraftField.RESPONSIBLE),
            warnings = warnings,
            provenance = provenance,
            inputTokens = original.split(Regex("\\s+")).count { it.isNotBlank() },
            outputTokens = 0,
        )
    }

    private fun extractType(text: String): EventType? = typeKeywords.entries.firstOrNull { (_, keywords) ->
        keywords.any(text::contains)
    }?.key

    private fun extractDate(text: String, receivedAt: Instant, zoneId: ZoneId): LocalDate? {
        val today = receivedAt.atZone(zoneId).toLocalDate()
        if (Regex("\\bamanha\\b").containsMatchIn(text)) return today.plusDays(1)
        if (Regex("\\bhoje\\b").containsMatchIn(text)) return today
        isoDate.find(text)?.groupValues?.get(1)?.let { return runCatching { LocalDate.parse(it) }.getOrNull() }
        shortDate.find(text)?.let { match ->
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].takeIf(String::isNotEmpty)?.toInt() ?: today.year
            return runCatching { LocalDate.of(if (year < 100) year + 2000 else year, month, day) }.getOrNull()
        }
        return null
    }

    private fun extractTimes(text: String): List<LocalTime> = timePattern.findAll(text)
        .mapNotNull { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val minute = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
            runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        .distinct()
        .sorted()
        .toList()

    private fun extractSchedule(text: String, times: List<LocalTime>, startAt: Instant?, zoneId: ZoneId): ScheduleExtraction {
        val repetitions = repetitionsPattern.find(text)?.groupValues?.get(1)?.toLongOrNull()
        val endAt = durationDaysPattern.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { days ->
            startAt?.plus(Duration.ofDays(days))
        }
        if (ambiguousDailyFrequency.containsMatchIn(text) && times.size < 2) {
            return ScheduleExtraction(null, "duas vezes ao dia", CareDraftFieldProvenance.NEEDS_REVIEW, true)
        }
        fixedHoursPattern.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { hours ->
            return ScheduleExtraction(
                ScheduleRule.fixed(Duration.ofHours(hours), repetitions, endAt),
                "a cada $hours hora(s)",
                CareDraftFieldProvenance.EXPLICIT,
            )
        }
        calendarPattern.find(text)?.let { match ->
            val count = match.groupValues[1].takeIf(String::isNotEmpty)?.toLong() ?: 1L
            val unitText = match.groupValues[2]
            val unit = when {
                unitText.startsWith("dia") -> CalendarIntervalUnit.DAY
                unitText.startsWith("semana") -> CalendarIntervalUnit.WEEK
                unitText.startsWith("mes") -> CalendarIntervalUnit.MONTH
                else -> CalendarIntervalUnit.YEAR
            }
            return ScheduleExtraction(
                ScheduleRule.calendar(unit, count, repetitions, endAt),
                match.value,
                CareDraftFieldProvenance.EXPLICIT,
            )
        }
        if (dailyPattern.containsMatchIn(text)) {
            if (times.isEmpty()) return ScheduleExtraction(null, "todos os dias", CareDraftFieldProvenance.NEEDS_REVIEW, true)
            return ScheduleExtraction(
                ScheduleRule.daily(times, repetitions, endAt),
                times.joinToString(", "),
                CareDraftFieldProvenance.EXPLICIT,
            )
        }
        if (weeklyPattern.containsMatchIn(text)) {
            return ScheduleExtraction(
                ScheduleRule.calendar(CalendarIntervalUnit.WEEK, 1, repetitions, endAt),
                "toda semana",
                CareDraftFieldProvenance.EXPLICIT,
            )
        }
        return ScheduleExtraction(
            ScheduleRule.oneTime(),
            "sem repetição informada",
            CareDraftFieldProvenance.SYSTEM_DEFAULT,
        )
    }

    private fun explicit(
        field: CareDraftField,
        value: String,
        evidence: MutableMap<CareDraftField, String>,
        provenance: MutableMap<CareDraftField, CareDraftFieldProvenance>,
    ) {
        evidence[field] = value.take(500)
        provenance[field] = CareDraftFieldProvenance.EXPLICIT
    }

    private fun normalized(
        field: CareDraftField,
        value: String,
        evidence: MutableMap<CareDraftField, String>,
        provenance: MutableMap<CareDraftField, CareDraftFieldProvenance>,
    ) {
        evidence[field] = value.take(500)
        provenance[field] = CareDraftFieldProvenance.NORMALIZED
    }

    private data class ScheduleExtraction(
        val rule: ScheduleRule?,
        val evidence: String,
        val provenance: CareDraftFieldProvenance,
        val ambiguous: Boolean = false,
    )

    companion object {
        private val typeKeywords = linkedMapOf(
            EventType.VACCINE to listOf("vacina", "vacinacao"),
            EventType.MEDICINE to listOf("remedio", "medicamento", "antibiotico", "comprimido", "dose"),
            EventType.SERVICE to listOf("banho", "tosa", "consulta", "servico"),
            EventType.DIARY to listOf("diario", "anotacao", "registro"),
            EventType.BREED to listOf("cio", "reproducao", "cruza"),
        )
        private val typeTitles = mapOf(
            EventType.VACCINE to "Vacina",
            EventType.MEDICINE to "Medicamento",
            EventType.SERVICE to "Cuidado",
            EventType.DIARY to "Registro",
            EventType.BREED to "Acompanhamento reprodutivo",
        )
        private val isoDate = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
        private val shortDate = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
        private val timePattern = Regex("(?<![/\\d])(\\d{1,2})(?::|h)(\\d{2})?\\b")
        private val fixedHoursPattern = Regex("a cada\\s+(\\d+)\\s*horas?\\b")
        private val calendarPattern = Regex("a cada\\s*(\\d+)?\\s*(dias?|semanas?|mes(?:es)?|anos?)\\b")
        private val repetitionsPattern = Regex("\\b(\\d+)\\s*vezes\\b")
        private val durationDaysPattern = Regex("por\\s+(\\d+)\\s*dias?\\b")
        private val ambiguousDailyFrequency = Regex("\\b(?:duas|2)\\s*vezes\\s+(?:ao|por)\\s+dia\\b")
        private val dailyPattern = Regex("\\b(?:todo|cada)\\s+dia\\b|\\bdiariamente\\b")
        private val weeklyPattern = Regex("\\btoda\\s+semana\\b|\\bsemanalmente\\b")
        private val clinicalRequest = Regex("\\b(diagnostico|diagnosticar|qual dose|prescrever|tratamento)\\b")

        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.forLanguageTag("pt-BR"))
    }
}
