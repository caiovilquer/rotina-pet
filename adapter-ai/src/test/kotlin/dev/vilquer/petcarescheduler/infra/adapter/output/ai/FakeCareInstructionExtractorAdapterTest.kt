package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionPetContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class FakeCareInstructionExtractorAdapterTest {
    private val registry = SimpleMeterRegistry()
    private val adapter = FakeCareInstructionExtractorAdapter(registry)
    private val json = jacksonObjectMapper()

    @Test
    fun `synthetic evaluation dataset satisfies deterministic quality gate`() {
        val cases = requireNotNull(javaClass.getResourceAsStream("/evals/care-draft-eval-v1.jsonl"))
            .bufferedReader().readLines().filter(String::isNotBlank).map { json.readValue<EvalCase>(it) }
        var exact = 0

        cases.forEach { case ->
            val result = adapter.extract(
                CareInstructionExtractionRequest(
                    instruction = case.instruction,
                    receivedAt = Instant.parse("2026-07-14T12:00:00Z"),
                    zoneId = ZoneId.of("America/Sao_Paulo"),
                    pets = listOf(CareInstructionPetContext(PetId(1), "Luna")),
                    context = context,
                ),
            )
            val actualMissing = result.missingFields.map(CareDraftField::name).toSet()
            if (result.fields.type?.name == case.expectedType &&
                result.fields.scheduleRule?.kind?.name == case.expectedSchedule &&
                actualMissing == case.missing.toSet()
            ) exact++
        }

        assertTrue(exact.toDouble() / cases.size >= 0.90, "quality gate: $exact/${cases.size}")
        assertEquals(cases.size.toDouble(), registry.counter("rotinapet.ai.requests", "operation", "care_draft", "outcome", "success").count())
    }

    @Test
    fun `twice daily without exact times abstains instead of inventing interval`() {
        val result = adapter.extract(
            CareInstructionExtractionRequest(
                "Remédio para Luna amanhã às 08:00 duas vezes ao dia",
                Instant.parse("2026-07-14T12:00:00Z"),
                ZoneId.of("America/Sao_Paulo"),
                listOf(CareInstructionPetContext(PetId(1), "Luna")),
                context,
            ),
        )

        assertEquals(EventType.MEDICINE, result.fields.type)
        assertTrue(CareDraftField.SCHEDULE in result.missingFields)
        assertTrue(result.warnings.any { it.code == "SCHEDULE_AMBIGUOUS" && it.blocking })
    }

    private data class EvalCase(
        val instruction: String,
        val expectedType: String?,
        val expectedSchedule: String?,
        val missing: List<String>,
    )

    companion object {
        private val context = AiInvocationContext(
            HouseholdId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
            TutorId(1),
        )
    }
}
