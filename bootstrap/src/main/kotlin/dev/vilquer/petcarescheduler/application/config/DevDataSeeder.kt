package dev.vilquer.petcarescheduler.application.config

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.finance.ExpenseCategory
import dev.vilquer.petcarescheduler.core.domain.health.HealthMeasurementType
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdActivity
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdActivityType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdInvitation
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdMember
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.core.domain.valueobject.PhoneNumber
import dev.vilquer.petcarescheduler.usecase.command.AssignCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.CompleteCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateExpenseCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateHealthMeasurementCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateHealthRecordCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateHouseholdHandoffCommand
import dev.vilquer.petcarescheduler.usecase.command.CreatePetCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateTutorCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateVeterinaryShareCommand
import dev.vilquer.petcarescheduler.usecase.command.RenameHouseholdCommand
import dev.vilquer.petcarescheduler.usecase.command.SearchCareOccurrencesQuery
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdActivityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdInvitationRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareOccurrenceUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CreatePetUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CreateTutorUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.ExpenseUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HealthMeasurementUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HealthRecordUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HouseholdContextUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HouseholdManagementUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.VeterinaryReportUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Popula o PostgreSQL local com um cenário completo para exercitar login,
 * famílias compartilhadas, pets, cuidados, saúde, finanças e convites.
 *
 * Ativo somente com `app.seed.enabled=true` (perfil `dev`).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "app.seed", name = ["enabled"], havingValue = "true")
class DevDataSeeder(
    private val tutors: TutorRepositoryPort,
    private val createTutor: CreateTutorUseCase,
    private val createPet: CreatePetUseCase,
    private val householdContext: HouseholdContextUseCase,
    private val householdManagement: HouseholdManagementUseCase,
    private val members: HouseholdMemberRepositoryPort,
    private val invitations: HouseholdInvitationRepositoryPort,
    private val activities: HouseholdActivityRepositoryPort,
    private val carePlans: CarePlanUseCase,
    private val careOccurrences: CareOccurrenceUseCase,
    private val healthRecords: HealthRecordUseCase,
    private val healthMeasurements: HealthMeasurementUseCase,
    private val expenses: ExpenseUseCase,
    private val veterinaryReport: VeterinaryReportUseCase,
    private val clock: ClockPort,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val marker = Email.of(Accounts.ANA.email).getOrThrow()
        if (tutors.findByEmail(marker) != null) {
            log.info("Dev seed já presente — pulando")
            return
        }

        log.info("Populando seed de desenvolvimento…")
        val now = clock.now()
        val nowLdt = now.toLocalDateTime()
        val nowInstant = now.toInstant()

        val anaId = register(Accounts.ANA)
        val brunoId = register(Accounts.BRUNO)
        val carlaId = register(Accounts.CARLA)
        val diegoId = register(Accounts.DIEGO)
        val elenaId = register(Accounts.ELENA)
        val felipeId = register(Accounts.FELIPE)

        val silvaAccess = householdContext.resolve(anaId)
        val silvaId = silvaAccess.householdId
        rename(silvaAccess, "Família Silva")
        join(silvaId, brunoId, HouseholdRole.CAREGIVER, nowInstant, "Bruno Costa entrou como cuidador")
        join(silvaId, carlaId, HouseholdRole.VIEWER, nowInstant, "Carla Mendes entrou como visitante")
        householdContext.setDefault(brunoId, silvaId)
        householdContext.setDefault(carlaId, silvaId)
        seedPendingInvitation(silvaId, anaId, nowInstant)

        val thor = pet(silvaAccess, anaId, "Thor", "Cão", "Golden Retriever", LocalDate.of(2020, 3, 12))
        val luna = pet(silvaAccess, anaId, "Luna", "Gato", "Siamês", LocalDate.of(2021, 8, 5))
        val mel = pet(silvaAccess, anaId, "Mel", "Coelho", "Mini Lion", LocalDate.of(2023, 1, 20))

        seedSilvaCares(silvaAccess, thor, luna, anaId, brunoId, nowLdt)
        seedSilvaHealth(silvaAccess, thor, luna, nowInstant)
        seedSilvaFinance(silvaAccess, thor, luna, nowInstant)
        householdManagement.createHandoff(
            CreateHouseholdHandoffCommand(
                toTutorId = brunoId,
                note = "Thor tomou a medicação da manhã. Luna ainda precisa do antipulgas à noite.",
            ),
            silvaAccess,
        )
        veterinaryReport.createShare(
            CreateVeterinaryShareCommand(
                petId = thor,
                label = "Consulta Thor — seed",
                from = LocalDate.now().minusMonths(6),
                to = LocalDate.now(),
                expiresInHours = 72,
                includeNotes = true,
                includeCosts = true,
            ),
            silvaAccess,
        )

        val ramosAccess = householdContext.resolve(diegoId)
        rename(ramosAccess, "Família Ramos")
        val bob = pet(ramosAccess, diegoId, "Bob", "Cão", "Vira-lata", LocalDate.of(2019, 11, 2))
        val nina = pet(ramosAccess, diegoId, "Nina", "Gato", "Persa", LocalDate.of(2022, 4, 18))
        seedRamos(ramosAccess, bob, nina, diegoId, nowLdt, nowInstant)

        val souzaAccess = householdContext.resolve(elenaId)
        val souzaId = souzaAccess.householdId
        rename(souzaAccess, "Família Souza")
        join(souzaId, felipeId, HouseholdRole.CAREGIVER, nowInstant, "Felipe Nunes entrou como cuidador")
        householdContext.setDefault(felipeId, souzaId)
        val pipoca = pet(souzaAccess, elenaId, "Pipoca", "Cão", "Poodle", LocalDate.of(2021, 6, 30))
        seedSouza(souzaAccess, pipoca, elenaId, felipeId, nowLdt, nowInstant)

        log.info(
            """
            |Dev seed pronto.
            |  Contas (senha: ${Accounts.PASSWORD}):
            |    ${Accounts.ANA.email}     — OWNER Família Silva (Thor, Luna, Mel)
            |    ${Accounts.BRUNO.email}   — CAREGIVER Família Silva
            |    ${Accounts.CARLA.email}   — VIEWER Família Silva
            |    ${Accounts.DIEGO.email}   — OWNER Família Ramos (Bob, Nina)
            |    ${Accounts.ELENA.email}   — OWNER Família Souza (Pipoca)
            |    ${Accounts.FELIPE.email}  — CAREGIVER Família Souza
            |  Convite pendente: ${PENDING_INVITE_EMAIL}
            |    token: $PENDING_INVITE_TOKEN
            """.trimMargin(),
        )
    }

    private fun register(account: Account): TutorId =
        createTutor.execute(
            CreateTutorCommand(
                firstName = account.firstName,
                lastName = account.lastName,
                email = Email.of(account.email).getOrThrow(),
                rawPassword = Accounts.PASSWORD,
                phoneNumber = PhoneNumber.of(account.phone).getOrThrow(),
            ),
        ).tutorId

    private fun rename(access: HouseholdAccess, name: String) {
        val summary = householdContext.list(access.actorTutorId).first { it.id == access.householdId.value }
        householdManagement.rename(
            RenameHouseholdCommand(access.householdId, summary.version ?: 0, name),
            access,
        )
    }

    private fun join(
        householdId: HouseholdId,
        tutorId: TutorId,
        role: HouseholdRole,
        at: java.time.Instant,
        summary: String,
    ) {
        members.save(
            HouseholdMember(
                householdId = householdId,
                tutorId = tutorId,
                role = role,
                joinedAt = at,
            ),
        )
        activities.save(
            HouseholdActivity(
                householdId = householdId,
                type = HouseholdActivityType.MEMBER_JOINED,
                actorTutorId = tutorId,
                targetTutorId = tutorId,
                summary = summary,
                happenedAt = at,
            ),
        )
    }

    private fun seedPendingInvitation(householdId: HouseholdId, invitedBy: TutorId, at: java.time.Instant) {
        invitations.save(
            HouseholdInvitation(
                householdId = householdId,
                email = PENDING_INVITE_EMAIL,
                role = HouseholdRole.CAREGIVER,
                tokenHash = sha256(PENDING_INVITE_TOKEN),
                activeKey = "${householdId.value}:$PENDING_INVITE_EMAIL",
                invitedByTutorId = invitedBy,
                expiresAt = at.plus(Duration.ofDays(7)),
                createdAt = at,
            ),
        )
    }

    private fun pet(
        access: HouseholdAccess,
        tutorId: TutorId,
        name: String,
        species: String,
        breed: String,
        birthdate: LocalDate,
    ): PetId =
        createPet.execute(
            CreatePetCommand(
                name = name,
                species = species,
                breed = breed,
                birthdate = birthdate,
                photoUrl = null,
                tutorId = tutorId,
            ),
            access,
        ).petId

    private fun seedSilvaCares(
        access: HouseholdAccess,
        thor: PetId,
        luna: PetId,
        anaId: TutorId,
        brunoId: TutorId,
        now: LocalDateTime,
    ) {
        carePlans.create(
            CreateCarePlanCommand(
                petId = thor,
                type = EventType.MEDICINE,
                title = "Antipulgas Thor",
                instructions = "Aplicar na nuca após o banho.",
                startAt = now.plusHours(2).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.MONTH, 1),
                reminderMinutesBefore = 60,
                responsibleTutorId = brunoId,
                estimatedCostAmount = BigDecimal("89.90"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = thor,
                type = EventType.DIARY,
                title = "Passeio da manhã",
                instructions = "30 minutos no parque.",
                startAt = now.minusMinutes(2).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.DAY, 1),
                reminderMinutesBefore = 15,
                responsibleTutorId = anaId,
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = luna,
                type = EventType.MEDICINE,
                title = "Insulina Luna",
                instructions = "0,5 UI subcutânea. Cuidado crítico.",
                startAt = now.plusMinutes(30).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.DAY, 1),
                reminderMinutesBefore = 10,
                responsibleTutorId = anaId,
                critical = true,
                escalationDelayMinutes = 30,
                escalationTutorId = anaId,
                estimatedCostAmount = BigDecimal("12.00"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = luna,
                type = EventType.SERVICE,
                title = "Tosa higiênica",
                instructions = null,
                startAt = now.plusDays(5).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.MONTH, 2),
                reminderMinutesBefore = 1440,
                responsibleTutorId = brunoId,
                estimatedCostAmount = BigDecimal("120.00"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = thor,
                type = EventType.VACCINE,
                title = "Reforço V10",
                instructions = "Levar carteirinha.",
                startAt = now.plusDays(14).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.oneTime(),
                reminderMinutesBefore = 2880,
                responsibleTutorId = anaId,
                estimatedCostAmount = BigDecimal("180.00"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )

        val dueSoon = careOccurrences.search(
            SearchCareOccurrencesQuery(
                from = now.minusDays(1).atZone(access.zoneId).toInstant(),
                to = now.plusDays(2).atZone(access.zoneId).toInstant(),
                petId = thor,
                status = CareOccurrenceStatus.SCHEDULED,
                page = 0,
                size = 10,
            ),
            access,
        ).items.firstOrNull()
        if (dueSoon != null) {
            careOccurrences.assign(
                AssignCareOccurrenceCommand(
                    occurrenceId = CareOccurrenceId(dueSoon.id),
                    expectedVersion = dueSoon.version ?: 0,
                    responsibleTutorId = brunoId,
                ),
                access,
            )
            careOccurrences.complete(
                CompleteCareOccurrenceCommand(
                    occurrenceId = CareOccurrenceId(dueSoon.id),
                    requestId = UUID.randomUUID(),
                    note = "Concluído no seed de desenvolvimento",
                ),
                HouseholdAccess(access.householdId, brunoId, HouseholdRole.CAREGIVER),
            )
        }
    }

    private fun seedSilvaHealth(
        access: HouseholdAccess,
        thor: PetId,
        luna: PetId,
        now: java.time.Instant,
    ) {
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = thor,
                type = HealthRecordType.VACCINE,
                occurredAt = now.minus(Duration.ofDays(40)),
                title = "V10 anual",
                notes = "Sem reações adversas.",
                productName = "Vanguard Plus",
                dosage = "1 dose",
                batchNumber = "LOT-V10-8821",
                professionalName = "Dra. Marina Alves",
                clinicName = "VetCare Moema",
                costAmount = BigDecimal("220.00"),
                currency = "BRL",
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = thor,
                type = HealthRecordType.CONSULTATION,
                occurredAt = now.minus(Duration.ofDays(12)),
                title = "Check-up rotina",
                notes = "Peso estável, pelagem boa.",
                productName = null,
                dosage = null,
                batchNumber = null,
                professionalName = "Dr. Paulo Reis",
                clinicName = "VetCare Moema",
                costAmount = BigDecimal("180.00"),
                currency = "BRL",
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = luna,
                type = HealthRecordType.MEDICATION,
                occurredAt = now.minus(Duration.ofDays(3)),
                title = "Ciclo de antibiótico",
                notes = "Finalizar em 7 dias.",
                productName = "Amoxicilina",
                dosage = "50 mg 2x/dia",
                batchNumber = "AMX-441",
                professionalName = "Dra. Marina Alves",
                clinicName = "VetCare Moema",
                costAmount = BigDecimal("65.50"),
                currency = "BRL",
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = luna,
                type = HealthRecordType.SYMPTOM,
                occurredAt = now.minus(Duration.ofDays(5)),
                title = "Espirros frequentes",
                notes = "Melhorou após limpeza do ambiente.",
                productName = null,
                dosage = null,
                batchNumber = null,
                professionalName = null,
                clinicName = null,
                costAmount = null,
                currency = null,
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = thor,
                type = HealthRecordType.EXAM,
                occurredAt = now.minus(Duration.ofDays(11)),
                title = "Hemograma completo",
                notes = "Resultados dentro da referência.",
                productName = null,
                dosage = null,
                batchNumber = null,
                professionalName = "Lab PetDiagnose",
                clinicName = "VetCare Moema",
                costAmount = BigDecimal("145.00"),
                currency = "BRL",
            ),
            access,
        )

        listOf(30L, 20L, 10L, 5L, 1L).forEachIndexed { index, daysAgo ->
            healthMeasurements.create(
                CreateHealthMeasurementCommand(
                    petId = thor,
                    type = HealthMeasurementType.WEIGHT,
                    value = BigDecimal("32.${index + 1}"),
                    measuredAt = now.minus(Duration.ofDays(daysAgo)),
                    notes = if (index == 0) "Peso pós-consulta" else null,
                ),
                access,
            )
        }
        healthMeasurements.create(
            CreateHealthMeasurementCommand(
                petId = luna,
                type = HealthMeasurementType.WEIGHT,
                value = BigDecimal("4.20"),
                measuredAt = now.minus(Duration.ofDays(2)),
                notes = null,
            ),
            access,
        )
        healthMeasurements.create(
            CreateHealthMeasurementCommand(
                petId = luna,
                type = HealthMeasurementType.TEMPERATURE,
                value = BigDecimal("38.5"),
                measuredAt = now.minus(Duration.ofDays(5)),
                notes = "Durante o sintoma de espirros",
            ),
            access,
        )
        healthMeasurements.create(
            CreateHealthMeasurementCommand(
                petId = thor,
                type = HealthMeasurementType.BODY_CONDITION_SCORE,
                value = BigDecimal("5"),
                measuredAt = now.minus(Duration.ofDays(12)),
                notes = "Ideal",
            ),
            access,
        )
    }

    private fun seedSilvaFinance(access: HouseholdAccess, thor: PetId, luna: PetId, now: java.time.Instant) {
        expenses.create(
            CreateExpenseCommand(
                petId = thor,
                category = ExpenseCategory.FOOD,
                description = "Ração premium 15 kg",
                amount = BigDecimal("289.90"),
                currency = "BRL",
                occurredAt = now.minus(Duration.ofDays(8)),
                notes = "Sachê incluso",
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = thor,
                category = ExpenseCategory.HYGIENE,
                description = "Banho e tosa",
                amount = BigDecimal("95.00"),
                currency = "BRL",
                occurredAt = now.minus(Duration.ofDays(4)),
                notes = null,
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = luna,
                category = ExpenseCategory.MEDICATION,
                description = "Antipulgas gatos",
                amount = BigDecimal("72.00"),
                currency = "BRL",
                occurredAt = now.minus(Duration.ofDays(15)),
                notes = null,
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = thor,
                category = ExpenseCategory.INSURANCE,
                description = "Plano mensal PetSeguro",
                amount = BigDecimal("59.90"),
                currency = "BRL",
                occurredAt = now.minus(Duration.ofDays(1)),
                notes = "Cobertura básica",
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = luna,
                category = ExpenseCategory.OTHER,
                description = "Arranhador torre",
                amount = BigDecimal("149.00"),
                currency = "BRL",
                occurredAt = now.minus(Duration.ofDays(20)),
                notes = null,
            ),
            access,
        )
    }

    private fun seedRamos(
        access: HouseholdAccess,
        bob: PetId,
        nina: PetId,
        diegoId: TutorId,
        now: LocalDateTime,
        nowInstant: java.time.Instant,
    ) {
        carePlans.create(
            CreateCarePlanCommand(
                petId = bob,
                type = EventType.DIARY,
                title = "Ração Bob",
                instructions = "2 xícaras pela manhã",
                startAt = now.plusHours(1).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.DAY, 1),
                reminderMinutesBefore = 0,
                responsibleTutorId = diegoId,
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = nina,
                type = EventType.MEDICINE,
                title = "Vermífugo Nina",
                instructions = null,
                startAt = now.plusDays(3).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.MONTH, 3),
                reminderMinutesBefore = 120,
                responsibleTutorId = diegoId,
                estimatedCostAmount = BigDecimal("45.00"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = bob,
                type = HealthRecordType.DAILY_CARE,
                occurredAt = nowInstant.minus(Duration.ofDays(1)),
                title = "Banho em casa",
                notes = null,
                productName = null,
                dosage = null,
                batchNumber = null,
                professionalName = null,
                clinicName = null,
                costAmount = null,
                currency = null,
            ),
            access,
        )
        healthMeasurements.create(
            CreateHealthMeasurementCommand(
                petId = bob,
                type = HealthMeasurementType.WEIGHT,
                value = BigDecimal("18.40"),
                measuredAt = nowInstant.minus(Duration.ofDays(7)),
                notes = null,
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = bob,
                category = ExpenseCategory.FOOD,
                description = "Ração econômica 20 kg",
                amount = BigDecimal("159.90"),
                currency = "BRL",
                occurredAt = nowInstant.minus(Duration.ofDays(6)),
                notes = null,
            ),
            access,
        )
    }

    private fun seedSouza(
        access: HouseholdAccess,
        pipoca: PetId,
        elenaId: TutorId,
        felipeId: TutorId,
        now: LocalDateTime,
        nowInstant: java.time.Instant,
    ) {
        carePlans.create(
            CreateCarePlanCommand(
                petId = pipoca,
                type = EventType.SERVICE,
                title = "Creche canina",
                instructions = "Deixar às 8h, buscar às 18h",
                startAt = now.plusDays(1).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.WEEK, 1),
                reminderMinutesBefore = 720,
                responsibleTutorId = felipeId,
                estimatedCostAmount = BigDecimal("80.00"),
                estimatedCostCurrency = "BRL",
            ),
            access,
        )
        carePlans.create(
            CreateCarePlanCommand(
                petId = pipoca,
                type = EventType.BREED,
                title = "Controle de cio",
                instructions = "Observar comportamento",
                startAt = now.plusDays(10).atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                scheduleRule = ScheduleRule.calendar(CalendarIntervalUnit.MONTH, 6),
                reminderMinutesBefore = 60,
                responsibleTutorId = elenaId,
            ),
            access,
        )
        healthRecords.create(
            CreateHealthRecordCommand(
                petId = pipoca,
                type = HealthRecordType.VACCINE,
                occurredAt = nowInstant.minus(Duration.ofDays(60)),
                title = "Antirrábica",
                notes = null,
                productName = "Rabisin",
                dosage = "1 dose",
                batchNumber = "RAB-991",
                professionalName = "Dr. Hugo Lima",
                clinicName = "Clínica Animal Norte",
                costAmount = BigDecimal("95.00"),
                currency = "BRL",
            ),
            access,
        )
        expenses.create(
            CreateExpenseCommand(
                petId = pipoca,
                category = ExpenseCategory.SERVICE,
                description = "Creche — semana passada",
                amount = BigDecimal("80.00"),
                currency = "BRL",
                occurredAt = nowInstant.minus(Duration.ofDays(7)),
                notes = null,
            ),
            access,
        )
        householdManagement.createHandoff(
            CreateHouseholdHandoffCommand(
                toTutorId = felipeId,
                note = "Pipoca fica inquieta se não passear depois da creche.",
            ),
            access,
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Account(
        val firstName: String,
        val lastName: String,
        val email: String,
        val phone: String,
    )

    companion object {
        const val PENDING_INVITE_EMAIL = "guest.pending@rotinapet.dev"
        const val PENDING_INVITE_TOKEN = "dev-pending-invite-token-for-guest-accept-flow-01"

        private object Accounts {
            const val PASSWORD = "Dev@12345"
            val ANA = Account("Ana", "Silva", "ana.silva@rotinapet.dev", "+5511999900001")
            val BRUNO = Account("Bruno", "Costa", "bruno.costa@rotinapet.dev", "+5511999900002")
            val CARLA = Account("Carla", "Mendes", "carla.mendes@rotinapet.dev", "+5511999900003")
            val DIEGO = Account("Diego", "Ramos", "diego.ramos@rotinapet.dev", "+5511999900004")
            val ELENA = Account("Elena", "Souza", "elena.souza@rotinapet.dev", "+5511999900005")
            val FELIPE = Account("Felipe", "Nunes", "felipe.nunes@rotinapet.dev", "+5511999900006")
        }
    }
}
