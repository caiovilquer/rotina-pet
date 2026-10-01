package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.health.HealthMeasurement
import dev.vilquer.petcarescheduler.core.domain.health.HealthMeasurementId
import dev.vilquer.petcarescheduler.core.domain.health.HealthMeasurementType
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecord
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordId
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.usecase.command.CreateHealthMeasurementCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateHealthRecordCommand
import dev.vilquer.petcarescheduler.usecase.command.SearchHealthRecordsQuery
import dev.vilquer.petcarescheduler.usecase.command.UpdateHealthMeasurementCommand
import dev.vilquer.petcarescheduler.usecase.command.UpdateHealthRecordCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthAttachmentDetails
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthMeasurementRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordAttachmentRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordFilter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.MediaAssetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdActivityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdActivityDetails
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOperation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HealthMeasurementUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HealthRecordUseCase
import dev.vilquer.petcarescheduler.usecase.result.HealthAttachmentResult
import dev.vilquer.petcarescheduler.usecase.result.HealthMeasurementResult
import dev.vilquer.petcarescheduler.usecase.result.HealthRecordResult
import dev.vilquer.petcarescheduler.usecase.result.HealthRecordsPageResult
import java.time.Duration
import java.time.Instant
import java.util.UUID

class HealthAppService(
    private val records: HealthRecordRepositoryPort,
    private val measurements: HealthMeasurementRepositoryPort,
    private val attachments: HealthRecordAttachmentRepositoryPort,
    private val media: MediaAssetRepositoryPort,
    private val pets: PetRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val activities: HouseholdActivityRepositoryPort = object : HouseholdActivityRepositoryPort {
        override fun save(activity: HouseholdActivity) = activity
        override fun listRecent(householdId: HouseholdId, limit: Int) = emptyList<HouseholdActivityDetails>()
    },
    private val knowledgeSources: KnowledgeSourceRepositoryPort? = null,
    private val knowledgeOutbox: KnowledgeIndexOutboxPort? = null,
    private val embeddingSignature: String = KnowledgeSourceFactory.FAKE_EMBEDDING_SIGNATURE,
) : HealthRecordUseCase, HealthMeasurementUseCase {

    fun create(command: CreateHealthRecordCommand, tutorId: TutorId): HealthRecordResult {
        if (!pets.existsForTutor(command.petId, tutorId)) throw NotFoundException("Pet não encontrado")
        return create(command, legacyAccess(tutorId))
    }
    fun update(command: UpdateHealthRecordCommand, tutorId: TutorId) = update(command, legacyAccess(tutorId))
    fun get(recordId: HealthRecordId, tutorId: TutorId) = get(recordId, legacyAccess(tutorId))
    fun search(query: SearchHealthRecordsQuery, tutorId: TutorId) = search(query, legacyAccess(tutorId))
    fun delete(recordId: HealthRecordId, expectedVersion: Long, tutorId: TutorId) = delete(recordId, expectedVersion, legacyAccess(tutorId))
    fun create(command: CreateHealthMeasurementCommand, tutorId: TutorId): HealthMeasurementResult {
        if (!pets.existsForTutor(command.petId, tutorId)) throw NotFoundException("Pet não encontrado")
        return create(command, legacyAccess(tutorId))
    }
    fun update(command: UpdateHealthMeasurementCommand, tutorId: TutorId) = update(command, legacyAccess(tutorId))
    fun list(petId: PetId, type: HealthMeasurementType?, from: Instant, to: Instant, tutorId: TutorId) =
        list(petId, type, from, to, legacyAccess(tutorId))
    fun delete(measurementId: HealthMeasurementId, expectedVersion: Long, tutorId: TutorId) = delete(measurementId, expectedVersion, legacyAccess(tutorId))

    private fun legacyAccess(tutorId: TutorId) = HouseholdAccess(
        HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000001")), tutorId, HouseholdRole.OWNER,
    )

    override fun create(command: CreateHealthRecordCommand, access: HouseholdAccess): HealthRecordResult = transaction.execute {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        val pet = requirePet(command.petId, access)
        validateOccurredAt(command.occurredAt)
        val now = clock.now().toInstant()
        val saved = records.save(
            HealthRecord(
                householdId = access.householdId,
                tutorId = access.actorTutorId,
                petId = command.petId,
                type = command.type,
                occurredAt = command.occurredAt,
                title = requiredText(command.title),
                notes = optionalText(command.notes),
                productName = optionalText(command.productName),
                dosage = optionalText(command.dosage),
                batchNumber = optionalText(command.batchNumber),
                professionalName = optionalText(command.professionalName),
                clinicName = optionalText(command.clinicName),
                costAmount = command.costAmount,
                currency = command.currency?.trim()?.uppercase(),
                createdByTutorId = access.actorTutorId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        activities.save(HouseholdActivity(
            householdId = access.householdId, type = HouseholdActivityType.HEALTH_RECORDED,
            actorTutorId = access.actorTutorId, petId = command.petId,
            summary = "${saved.title} foi adicionado ao histórico de saúde", happenedAt = now,
        ))
        prepareKnowledge(saved, now)
        saved.toResult(emptyList())
    }

    override fun update(command: UpdateHealthRecordCommand, access: HouseholdAccess): HealthRecordResult = transaction.execute {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        val current = records.findByIdAndHouseholdForUpdate(command.recordId, access.householdId)
            ?: throw NotFoundException("Registro de saúde não encontrado")
        requireVersion(current.version, command.expectedVersion)
        validateOccurredAt(command.occurredAt)
        val saved = records.save(
            current.copy(
                type = command.type,
                occurredAt = command.occurredAt,
                title = requiredText(command.title),
                notes = optionalText(command.notes),
                productName = optionalText(command.productName),
                dosage = optionalText(command.dosage),
                batchNumber = optionalText(command.batchNumber),
                professionalName = optionalText(command.professionalName),
                clinicName = optionalText(command.clinicName),
                costAmount = command.costAmount,
                currency = command.currency?.trim()?.uppercase(),
                updatedAt = clock.now().toInstant(),
            ),
        )
        prepareKnowledge(saved, saved.updatedAt)
        saved.toResult(attachmentResults(saved.id))
    }

    override fun get(recordId: HealthRecordId, access: HouseholdAccess): HealthRecordResult {
        requirePermission(access, HouseholdPermission.VIEW)
        val record = records.findByIdAndHousehold(recordId, access.householdId)
            ?: throw NotFoundException("Registro de saúde não encontrado")
        return record.toResult(attachmentResults(record.id))
    }

    override fun search(query: SearchHealthRecordsQuery, access: HouseholdAccess): HealthRecordsPageResult {
        requirePermission(access, HouseholdPermission.VIEW)
        requirePet(query.petId, access)
        require(query.page >= 0 && query.size in 1..50) { "health_page_invalid" }
        validatePeriod(query.from, query.to, MAX_RECORD_PERIOD)
        val filter = HealthRecordFilter(query.petId, query.from, query.to, query.type)
        val items = records.searchByHousehold(access.householdId, filter, query.page, query.size)
        val attachmentMap = attachments.listByRecordIds(items.map { it.id })
        return HealthRecordsPageResult(
            items.map { it.toResult(attachmentMap[it.id].orEmpty().map(::attachmentResult)) },
            records.countByHousehold(access.householdId, filter),
            query.page,
            query.size,
        )
    }

    override fun delete(recordId: HealthRecordId, expectedVersion: Long, access: HouseholdAccess) {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        transaction.execute {
            val record = records.findByIdAndHouseholdForUpdate(recordId, access.householdId)
                ?: throw NotFoundException("Registro de saúde não encontrado")
            requireVersion(record.version, expectedVersion)
            attachments.listByRecordIds(listOf(recordId))[recordId].orEmpty().forEach { item ->
                media.findById(item.mediaAssetId)?.let { media.save(it.copy(status = MediaStatus.PENDING_DELETE)) }
                deleteKnowledge(access.householdId, KnowledgeSourceType.HEALTH_ATTACHMENT, item.mediaAssetId, clock.now().toInstant())
            }
            deleteKnowledge(access.householdId, KnowledgeSourceType.HEALTH_RECORD, record.id.value, clock.now().toInstant())
            records.delete(recordId)
        }
    }

    override fun create(command: CreateHealthMeasurementCommand, access: HouseholdAccess): HealthMeasurementResult {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        val pet = requirePet(command.petId, access)
        validateOccurredAt(command.measuredAt)
        val now = clock.now().toInstant()
        return measurements.save(
            HealthMeasurement(
                householdId = access.householdId,
                tutorId = access.actorTutorId,
                petId = command.petId,
                type = command.type,
                value = command.value,
                measuredAt = command.measuredAt,
                notes = optionalText(command.notes),
                createdByTutorId = access.actorTutorId,
                createdAt = now,
                updatedAt = now,
            ),
        ).toResult()
    }

    override fun update(command: UpdateHealthMeasurementCommand, access: HouseholdAccess): HealthMeasurementResult = transaction.execute {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        val current = measurements.findByIdAndHouseholdForUpdate(command.measurementId, access.householdId)
            ?: throw NotFoundException("Medição não encontrada")
        requireVersion(current.version, command.expectedVersion)
        validateOccurredAt(command.measuredAt)
        measurements.save(
            current.copy(
                value = command.value,
                measuredAt = command.measuredAt,
                notes = optionalText(command.notes),
                updatedAt = clock.now().toInstant(),
            ),
        ).toResult()
    }

    override fun list(
        petId: PetId,
        type: HealthMeasurementType?,
        from: Instant,
        to: Instant,
        access: HouseholdAccess,
    ): List<HealthMeasurementResult> {
        requirePermission(access, HouseholdPermission.VIEW)
        requirePet(petId, access)
        validatePeriod(from, to, MAX_MEASUREMENT_PERIOD)
        return measurements.listByHousehold(access.householdId, petId, type, from, to, MAX_MEASUREMENTS).map { it.toResult() }
    }

    override fun delete(measurementId: HealthMeasurementId, expectedVersion: Long, access: HouseholdAccess) {
        requirePermission(access, HouseholdPermission.RECORD_HEALTH)
        transaction.execute {
            val current = measurements.findByIdAndHouseholdForUpdate(measurementId, access.householdId)
                ?: throw NotFoundException("Medição não encontrada")
            requireVersion(current.version, expectedVersion)
            measurements.delete(measurementId)
        }
    }

    private fun requirePet(petId: PetId, access: HouseholdAccess) =
        pets.findByIdAndHousehold(petId, access.householdId) ?: throw NotFoundException("Pet não encontrado")

    private fun requirePermission(access: HouseholdAccess, permission: HouseholdPermission) {
        if (!access.can(permission)) throw dev.vilquer.petcarescheduler.application.exception.ForbiddenException("Seu papel nesta família não permite esta ação")
    }

    private fun validateOccurredAt(value: Instant) {
        require(!value.isAfter(clock.now().toInstant().plus(FUTURE_TOLERANCE))) { "health_date_in_future" }
    }

    private fun validatePeriod(from: Instant, to: Instant, max: Duration) {
        require(to.isAfter(from)) { "health_period_invalid" }
        require(Duration.between(from, to) <= max) { "health_period_too_large" }
    }

    private fun requireVersion(actual: Long?, expected: Long) {
        if (actual != expected) throw ConflictException("Este registro foi alterado. Atualize a página e tente novamente")
    }

    private fun requiredText(value: String) = value.trim()
    private fun optionalText(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
    private fun attachmentResults(id: HealthRecordId) =
        attachments.listByRecordIds(listOf(id))[id].orEmpty().map(::attachmentResult)

    private fun attachmentResult(item: HealthAttachmentDetails) = HealthAttachmentResult(
        item.id,
        item.mediaAssetId,
        item.originalFilename,
        item.contentType,
        item.sizeBytes,
        "/api/v1/health-attachments/${item.mediaAssetId}/download-url",
    )

    private fun HealthRecord.toResult(items: List<HealthAttachmentResult>) = HealthRecordResult(
        id.value, version, petId.value, type, occurredAt, title, notes, productName, dosage, batchNumber,
        professionalName, clinicName, costAmount, currency, createdByTutorId.value, items,
    )

    private fun HealthMeasurement.toResult() = HealthMeasurementResult(
        id.value, version, petId.value, type, value, unit, measuredAt, notes, createdByTutorId.value,
    )

    private fun prepareKnowledge(record: HealthRecord, at: Instant) {
        val sourceRepository = knowledgeSources ?: return
        val outboxRepository = knowledgeOutbox ?: return
        val prepared = sourceRepository.prepare(KnowledgeSourceFactory.healthRecord(record, at, embeddingSignature))
        if (prepared.changed) {
            outboxRepository.enqueue(
                prepared.source.id,
                KnowledgeIndexOperation.UPSERT,
                "upsert:${prepared.source.id}:${prepared.source.checksum}",
                at,
            )
        }
    }

    private fun deleteKnowledge(householdId: HouseholdId, type: KnowledgeSourceType, resourceId: UUID, at: Instant) {
        val sourceRepository = knowledgeSources ?: return
        val outboxRepository = knowledgeOutbox ?: return
        sourceRepository.markDeleted(householdId, type, resourceId, at)?.let { source ->
            outboxRepository.enqueue(source.id, KnowledgeIndexOperation.DELETE, "delete:${source.id}:${source.updatedAt}", at)
        }
    }

    companion object {
        private val FUTURE_TOLERANCE = Duration.ofMinutes(5)
        private val MAX_RECORD_PERIOD = Duration.ofDays(365L * 20)
        private val MAX_MEASUREMENT_PERIOD = Duration.ofDays(365L * 10)
        private const val MAX_MEASUREMENTS = 500
    }
}
