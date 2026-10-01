package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStatus
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFieldProvenance
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFields
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftWarning
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedCareDraft
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class OpenAiCareInstructionExtractorAdapter(
    private val client: OpenAIClient,
    private val settings: AiAdapterSettings,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) : CareInstructionExtractorPort {
    override val provider = "openai"
    override val model = settings.careDraftModel
    override val promptVersion = "care-draft-v2-openai"
    private val prompt = resourceText("prompts/care-draft-v2-openai.md")
    private val safetyIdentifier = SafetyIdentifier(settings.safetyIdentifierKey)

    override fun extract(request: CareInstructionExtractionRequest): ExtractedCareDraft {
        val sample = Timer.start(meterRegistry)
        return try {
            val petReferences = request.pets.mapIndexed { index, pet -> "pet-${index + 1}" to pet }.toMap()
            val input = objectMapper.writeValueAsString(
                mapOf(
                    "instruction" to request.instruction,
                    "receivedAt" to request.receivedAt.toString(),
                    "timezone" to request.zoneId.id,
                    "pets" to petReferences.map { (reference, pet) -> mapOf("reference" to reference, "name" to pet.name) },
                ),
            )
            val params = ResponseCreateParams.builder()
                .input(input)
                .instructions(prompt)
                .store(false)
                .reasoning(Reasoning.builder().effort(ReasoningEffort.of(settings.reasoningEffort)).build())
                .safetyIdentifier(safetyIdentifier.forContext(request.context))
                .maxOutputTokens(2_000)
                .text(OpenAiStructuredOutputs.CareDraftOutput::class.java)
                .model(model)
                .build()
            val response = client.responses().create(params)
            if (response.status().orElse(null) != ResponseStatus.COMPLETED) {
                throw OpenAiOutputException("care_draft_response_incomplete")
            }
            val contents = response.output().flatMap { item ->
                item.message().map { message -> message.content() }.orElse(emptyList())
            }
            if (contents.any { it.refusal().isPresent }) throw OpenAiOutputException("care_draft_refused")
            val output = contents.mapNotNull { it.outputText().orElse(null) }.singleOrNull()
                ?: throw OpenAiOutputException("care_draft_output_missing")
            val usage = response.usage().orElse(null)
            mapOutput(output, petReferences, request.zoneId).copy(
                inputTokens = usage?.inputTokens()?.toInt(),
                outputTokens = usage?.outputTokens()?.toInt(),
            ).also {
                meterRegistry.counter("rotinapet.ai.requests", "operation", "care_draft", "provider", provider, "outcome", "success").increment()
            }
        } catch (exception: RuntimeException) {
            meterRegistry.counter("rotinapet.ai.requests", "operation", "care_draft", "provider", provider, "outcome", "error").increment()
            val failure = classifyOpenAiFailure(exception)
            throw CareInstructionExtractionException(failure.code, failure.retryable, exception)
        } finally {
            sample.stop(meterRegistry.timer("rotinapet.ai.latency", "operation", "care_draft", "provider", provider, "model", model))
        }
    }

    private fun mapOutput(
        output: OpenAiStructuredOutputs.CareDraftOutput,
        pets: Map<String, dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionPetContext>,
        requestZone: java.time.ZoneId,
    ): ExtractedCareDraft {
        val petReference = output.petReference.orElse(null)
        val petId = petReference?.let { pets[it]?.id ?: throw OpenAiOutputException("care_draft_pet_reference_invalid") }
        val evidence = linkedMapOf<CareDraftField, String>()
        val provenance = linkedMapOf<CareDraftField, CareDraftFieldProvenance>()
        output.evidence.forEach { item ->
            val field = CareDraftField.valueOf(item.field.name)
            val value = item.evidence.trim()
            if (value.isEmpty() || value.length > 500) throw OpenAiOutputException("care_draft_evidence_invalid")
            evidence[field] = value
            provenance[field] = when (item.provenance) {
                OpenAiStructuredOutputs.DraftProvenance.EXPLICIT -> CareDraftFieldProvenance.EXPLICIT
                OpenAiStructuredOutputs.DraftProvenance.NORMALIZED -> CareDraftFieldProvenance.NORMALIZED
                OpenAiStructuredOutputs.DraftProvenance.NEEDS_REVIEW -> CareDraftFieldProvenance.NEEDS_REVIEW
                OpenAiStructuredOutputs.DraftProvenance.MISSING -> CareDraftFieldProvenance.MISSING
            }
        }
        val fields = CareDraftFields(
            petId = petId,
            type = output.type.orElse(null)?.let(EventType::valueOf),
            title = output.title.orElse(null)?.trim()?.takeIf(String::isNotEmpty),
            instructions = output.instructions.orElse(null)?.trim()?.takeIf(String::isNotEmpty),
            startAt = output.startAt.orElse(null)?.let(Instant::parse),
            zoneId = output.timezone.orElse(null)?.let { value ->
                val parsed = runCatching { java.time.ZoneId.of(value) }
                    .getOrElse { throw OpenAiOutputException("care_draft_timezone_invalid") }
                if (parsed != requestZone) throw OpenAiOutputException("care_draft_timezone_mismatch")
                parsed
            } ?: requestZone,
            scheduleRule = output.schedule.orElse(null)?.let(::schedule),
        )
        return ExtractedCareDraft(
            fields = fields,
            evidence = evidence,
            missingFields = output.missingFields.mapTo(linkedSetOf()) { CareDraftField.valueOf(it.name) },
            warnings = output.warnings.map { warning ->
                val code = warning.code.trim()
                val message = warning.message.trim()
                if (!code.matches(Regex("^[A-Z0-9_]{3,80}$")) || message.isEmpty() || message.length > 500) {
                    throw OpenAiOutputException("care_draft_warning_invalid")
                }
                CareDraftWarning(code, message, warning.blocking)
            },
            provenance = provenance,
        )
    }

    private fun schedule(value: OpenAiStructuredOutputs.Schedule): ScheduleRule {
        val repetitions = value.repetitions.orElse(null)
        val endAt = value.endAt.orElse(null)?.let(Instant::parse)
        return when (value.kind) {
            OpenAiStructuredOutputs.ScheduleKind.ONE_TIME -> ScheduleRule.oneTime()
            OpenAiStructuredOutputs.ScheduleKind.CALENDAR_INTERVAL -> ScheduleRule.calendar(
                CalendarIntervalUnit.valueOf(value.calendarUnit.orElseThrow()),
                value.intervalCount.orElseThrow(), repetitions, endAt,
            )
            OpenAiStructuredOutputs.ScheduleKind.FIXED_INTERVAL -> ScheduleRule.fixed(
                Duration.ofMinutes(value.fixedIntervalMinutes.orElseThrow()), repetitions, endAt,
            )
            OpenAiStructuredOutputs.ScheduleKind.DAILY_TIMES -> ScheduleRule.daily(
                value.dailyTimes.map(LocalTime::parse).distinct().sorted(), repetitions, endAt,
            )
        }
    }
}
