package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.health.HealthMeasurementType
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.usecase.command.FinanceOverviewQuery
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceFilter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CarePlanRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthMeasurementRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordFilter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.FinanceOverviewUseCase
import dev.vilquer.petcarescheduler.usecase.result.AssistantCitationResult
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class PetHistoryIntent {
    LAST_VACCINE,
    MEDICATIONS,
    OVERDUE_CARE,
    TODAY_CARE,
    WEIGHT_HISTORY,
    TEMPERATURE_HISTORY,
    FINANCES,
    CARE_RESPONSIBLES,
    DOCUMENT_SEARCH,
    CLINICAL_REQUEST,
}

data class StructuredPetHistoryAnswer(
    val answer: String,
    val citations: List<AssistantCitationResult>,
    val insufficientEvidence: Boolean,
    val suggestedFollowUps: List<String> = emptyList(),
)

class StructuredPetHistoryCatalog(
    private val records: HealthRecordRepositoryPort,
    private val measurements: HealthMeasurementRepositoryPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val finances: FinanceOverviewUseCase? = null,
    private val plans: CarePlanRepositoryPort? = null,
    private val members: HouseholdMemberRepositoryPort? = null,
) {
    fun classify(question: String): PetHistoryIntent {
        val value = normalize(question)
        if (CLINICAL_TERMS.any(value::contains)) return PetHistoryIntent.CLINICAL_REQUEST
        return when {
            "vacina" in value || "vacinacao" in value -> PetHistoryIntent.LAST_VACCINE
            "medicamento" in value || "remedio" in value || "medicacao" in value -> PetHistoryIntent.MEDICATIONS
            "atrasad" in value || "pendente" in value -> PetHistoryIntent.OVERDUE_CARE
            ("hoje" in value && ("agenda" in value || "cuidado" in value || "rotina" in value)) -> PetHistoryIntent.TODAY_CARE
            "peso" in value || "pesagem" in value -> PetHistoryIntent.WEIGHT_HISTORY
            "temperatura" in value || "febre" in value -> PetHistoryIntent.TEMPERATURE_HISTORY
            FINANCE_TERMS.any(value::contains) -> PetHistoryIntent.FINANCES
            "quem cuida" in value || ("responsavel" in value && CARE_TERMS.any(value::contains)) -> PetHistoryIntent.CARE_RESPONSIBLES
            else -> PetHistoryIntent.DOCUMENT_SEARCH
        }
    }

    fun execute(intent: PetHistoryIntent, petId: PetId, access: HouseholdAccess, now: Instant): StructuredPetHistoryAnswer = when (intent) {
        PetHistoryIntent.LAST_VACCINE -> lastRecord(HealthRecordType.VACCINE, "vacina", petId, access, now)
        PetHistoryIntent.MEDICATIONS -> medicationHistory(petId, access, now)
        PetHistoryIntent.OVERDUE_CARE -> careList(petId, access, Instant.EPOCH, now, "atrasado")
        PetHistoryIntent.TODAY_CARE -> {
            val day = now.atZone(access.zoneId).toLocalDate()
            careList(petId, access, day.atStartOfDay(access.zoneId).toInstant(), day.plusDays(1).atStartOfDay(access.zoneId).toInstant(), "de hoje")
        }
        PetHistoryIntent.WEIGHT_HISTORY -> measurementHistory(HealthMeasurementType.WEIGHT, "peso", "kg", petId, access, now)
        PetHistoryIntent.TEMPERATURE_HISTORY -> measurementHistory(HealthMeasurementType.TEMPERATURE, "temperatura", "°C", petId, access, now)
        PetHistoryIntent.FINANCES -> financeOverview(petId, access, now)
        PetHistoryIntent.CARE_RESPONSIBLES -> careResponsibles(petId, access)
        PetHistoryIntent.CLINICAL_REQUEST -> StructuredPetHistoryAnswer(
            "O RotinaPet organiza informações registradas, mas não faz diagnóstico, prescrição ou recomendação de dose. Procure um profissional veterinário para orientação clínica.",
            emptyList(),
            false,
            listOf("Posso localizar registros e documentos já salvos sobre este pet."),
        )
        PetHistoryIntent.DOCUMENT_SEARCH -> error("document_search_is_not_a_structured_tool")
    }

    private fun lastRecord(type: HealthRecordType, label: String, petId: PetId, access: HouseholdAccess, now: Instant): StructuredPetHistoryAnswer {
        val item = records.searchByHousehold(
            access.householdId,
            HealthRecordFilter(petId, Instant.EPOCH, now.plusSeconds(300), type),
            0,
            1,
        ).firstOrNull() ?: return noData("Não encontrei $label registrada para este pet.")
        val date = formatDate(item.occurredAt, access.zoneId)
        val details = listOfNotNull(item.productName, item.clinicName).joinToString(" · ")
        return StructuredPetHistoryAnswer(
            "A última $label registrada foi “${item.title}” em $date${details.takeIf(String::isNotEmpty)?.let { ", $it" }.orEmpty()}.",
            listOf(recordCitation(item.id.value, item.title, "$date${details.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()}")),
            false,
        )
    }

    private fun medicationHistory(petId: PetId, access: HouseholdAccess, now: Instant): StructuredPetHistoryAnswer {
        val items = records.searchByHousehold(
            access.householdId,
            HealthRecordFilter(petId, now.minusSeconds(DAYS_365), now.plusSeconds(300), HealthRecordType.MEDICATION),
            0,
            10,
        )
        if (items.isEmpty()) return noData("Não encontrei medicamentos registrados nos últimos 12 meses.")
        val summary = items.joinToString("; ") { "${it.productName ?: it.title} em ${formatDate(it.occurredAt, access.zoneId)}" }
        return StructuredPetHistoryAnswer(
            "Nos últimos 12 meses, encontrei: $summary.",
            items.map { recordCitation(it.id.value, it.title, it.productName ?: formatDate(it.occurredAt, access.zoneId)) },
            false,
        )
    }

    private fun careList(petId: PetId, access: HouseholdAccess, from: Instant, to: Instant, label: String): StructuredPetHistoryAnswer {
        val items = occurrences.searchByHousehold(
            access.householdId,
            CareOccurrenceFilter(from, to, petId, status = CareOccurrenceStatus.SCHEDULED),
            0,
            20,
        )
        if (items.isEmpty()) return noData("Não encontrei cuidados $label para este pet.")
        val summary = items.joinToString("; ") { "${it.title} — ${formatDateTime(it.dueAt, access.zoneId)}" }
        return StructuredPetHistoryAnswer(
            "Encontrei ${items.size} cuidado(s) $label: $summary.",
            items.map {
                AssistantCitationResult(
                    KnowledgeSourceType.CARE_PLAN, it.planId.value, it.id.value, it.title, null,
                    formatDateTime(it.dueAt, access.zoneId), null,
                )
            },
            false,
        )
    }

    private fun measurementHistory(
        type: HealthMeasurementType,
        label: String,
        unit: String,
        petId: PetId,
        access: HouseholdAccess,
        now: Instant,
    ): StructuredPetHistoryAnswer {
        val items = measurements.listByHousehold(access.householdId, petId, type, now.minusSeconds(DAYS_365), now.plusSeconds(300), 24)
        if (items.isEmpty()) return noData("Não encontrei medições de $label nos últimos 12 meses.")
        val selected = if (items.size <= 6) items else items.filterIndexed { index, _ -> index == 0 || index == items.lastIndex || index % (items.size / 5).coerceAtLeast(1) == 0 }.take(7)
        val summary = selected.joinToString("; ") { "${it.value.stripTrailingZeros().toPlainString()} $unit em ${formatDate(it.measuredAt, access.zoneId)}" }
        return StructuredPetHistoryAnswer(
            "A evolução registrada de $label foi: $summary.",
            selected.map {
                AssistantCitationResult(
                    KnowledgeSourceType.HEALTH_MEASUREMENT, it.id.value, it.id.value,
                    "${label.replaceFirstChar(Char::uppercase)} de ${formatDate(it.measuredAt, access.zoneId)}", null,
                    "${it.value.stripTrailingZeros().toPlainString()} $unit", null,
                )
            },
            false,
        )
    }

    private fun financeOverview(petId: PetId, access: HouseholdAccess, now: Instant): StructuredPetHistoryAnswer {
        val service = finances ?: return noData("A consulta financeira estruturada não está disponível agora.")
        val today = now.atZone(access.zoneId).toLocalDate()
        val overview = service.overview(
            FinanceOverviewQuery(today.minusDays(364), today, today.plusDays(30), petId),
            access,
        )
        if (overview.realized.isEmpty() && overview.forecast.isEmpty()) {
            return noData("Não encontrei custos realizados ou previstos para este pet no período consultado.")
        }
        val realized = overview.realized.joinToString { "${it.currency} ${it.total.stripTrailingZeros().toPlainString()}" }
        val forecast = overview.forecast.joinToString { "${it.currency} ${it.amount.stripTrailingZeros().toPlainString()}" }
        val sections = buildList {
            if (realized.isNotEmpty()) add("custos registrados nos últimos 12 meses: $realized")
            if (forecast.isNotEmpty()) add("previsão para os próximos 30 dias: $forecast")
        }
        return StructuredPetHistoryAnswer(
            "Para este pet, encontrei ${sections.joinToString("; ")}.",
            overview.upcoming.take(20).map {
                AssistantCitationResult(
                    KnowledgeSourceType.CARE_PLAN, it.occurrenceId, it.occurrenceId, it.title, null,
                    "${formatDateTime(it.dueAt, access.zoneId)} · ${it.currency} ${it.amount.stripTrailingZeros().toPlainString()}", null,
                )
            },
            false,
        )
    }

    private fun careResponsibles(petId: PetId, access: HouseholdAccess): StructuredPetHistoryAnswer {
        val planRepository = plans ?: return noData("A consulta de responsáveis não está disponível agora.")
        val memberRepository = members ?: return noData("A consulta de responsáveis não está disponível agora.")
        val active = planRepository.listByHousehold(access.householdId, petId, true, 0, 100)
        if (active.isEmpty()) return noData("Não encontrei planos ativos com responsável para este pet.")
        val names = memberRepository.listDetails(access.householdId).associate {
            it.member.tutorId to listOfNotNull(it.firstName, it.lastName).joinToString(" ")
        }
        val summary = active.joinToString("; ") { "${it.title}: ${names[it.responsibleTutorId] ?: "membro da família"}" }
        return StructuredPetHistoryAnswer(
            "Responsáveis nos planos ativos: $summary.",
            active.map {
                AssistantCitationResult(
                    KnowledgeSourceType.CARE_PLAN, it.id.value, it.id.value, it.title, null,
                    names[it.responsibleTutorId] ?: "Responsável registrado no plano", null,
                )
            },
            false,
        )
    }

    private fun recordCitation(id: java.util.UUID, title: String, excerpt: String) = AssistantCitationResult(
        KnowledgeSourceType.HEALTH_RECORD, id, id, title, null, excerpt.take(500), null,
    )

    private fun noData(message: String) = StructuredPetHistoryAnswer(
        message,
        emptyList(),
        true,
        listOf("Confira se o registro foi adicionado ao histórico de saúde."),
    )

    private fun formatDate(value: Instant, zoneId: ZoneId) = DATE_FORMAT.format(value.atZone(zoneId))
    private fun formatDateTime(value: Instant, zoneId: ZoneId) = DATE_TIME_FORMAT.format(value.atZone(zoneId))

    companion object {
        private const val DAYS_365 = 365L * 24 * 60 * 60
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
        private val CLINICAL_TERMS = setOf(
            "diagnost", "o que ele tem", "qual doenca", "prescrev", "prescricao", "qual dose",
            "dosagem", "devo dar", "tratamento para",
        )
        private val FINANCE_TERMS = setOf("custo", "gasto", "despesa", "previsao financeira")
        private val CARE_TERMS = setOf("cuidado", "plano", "rotina", "tarefa")

        fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
