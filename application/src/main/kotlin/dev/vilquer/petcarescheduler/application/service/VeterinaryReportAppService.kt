package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.entity.Pet
import dev.vilquer.petcarescheduler.core.domain.health.*
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.core.domain.media.MediaPurpose
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.core.domain.report.*
import dev.vilquer.petcarescheduler.usecase.command.*
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.VeterinaryReportUseCase
import dev.vilquer.petcarescheduler.usecase.result.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class VeterinaryReportAppService(
    private val records: HealthRecordRepositoryPort,
    private val measurements: HealthMeasurementRepositoryPort,
    private val attachments: HealthRecordAttachmentRepositoryPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val pets: PetRepositoryPort,
    private val shares: VeterinaryShareRepositoryPort,
    private val media: MediaAssetRepositoryPort,
    private val storage: ObjectStoragePort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val households: HouseholdRepositoryPort? = null,
) : VeterinaryReportUseCase {

    override fun summary(query: VeterinarySummaryQuery, access: HouseholdAccess): VeterinarySummaryResult {
        requirePermission(access, HouseholdPermission.VIEW)
        return buildSummary(query, access.householdId, Disclosure.ALL)
    }

    override fun createShare(command: CreateVeterinaryShareCommand, access: HouseholdAccess): VeterinaryShareCreatedResult {
        requirePermission(access, HouseholdPermission.SHARE_VETERINARY_SUMMARY)
        pets.findByIdAndHousehold(command.petId, access.householdId) ?: throw NotFoundException("Pet não encontrado")
        require(command.expiresInHours in ALLOWED_EXPIRY_HOURS) { "veterinary_share_expiry_invalid" }
        val scope = VeterinaryShareScope(command.from, command.to, command.includeNotes, command.includeCosts, command.includeDocuments)
        val now = clock.now().toInstant()
        val rawToken = token()
        val saved = transaction.execute {
            if (shares.countActive(access.householdId, now) >= MAX_ACTIVE_SHARES) {
                throw ConflictException("Revogue um link antigo antes de criar outro")
            }
            shares.save(VeterinaryShare(
                householdId = access.householdId, petId = command.petId, createdByTutorId = access.actorTutorId,
                label = command.label.trim(), tokenHash = hash(rawToken), scope = scope,
                expiresAt = now.plus(Duration.ofHours(command.expiresInHours)), createdAt = now,
            ))
        }
        return VeterinaryShareCreatedResult(saved.id.value, rawToken, saved.expiresAt)
    }

    override fun listShares(petId: dev.vilquer.petcarescheduler.core.domain.entity.PetId?, access: HouseholdAccess): List<VeterinaryShareResult> {
        requirePermission(access, HouseholdPermission.SHARE_VETERINARY_SUMMARY)
        petId?.let { pets.findByIdAndHousehold(it, access.householdId) ?: throw NotFoundException("Pet não encontrado") }
        return shares.list(access.householdId, petId, 100).map { it.toResult() }
    }

    override fun revoke(command: RevokeVeterinaryShareCommand, access: HouseholdAccess) {
        requirePermission(access, HouseholdPermission.SHARE_VETERINARY_SUMMARY)
        transaction.execute {
            val share = shares.findByIdAndHouseholdForUpdate(command.shareId, access.householdId)
                ?: throw NotFoundException("Link não encontrado")
            if (share.version != command.expectedVersion) throw ConflictException("Este link foi alterado. Atualize e tente novamente")
            if (share.revokedAt == null) shares.save(share.revoke(clock.now().toInstant()))
        }
    }

    override fun publicSummary(command: ResolveVeterinaryShareCommand): PublicVeterinarySummaryResult = transaction.execute {
        val share = activeShare(command.token)
        val now = clock.now().toInstant()
        shares.save(share.accessed(now))
        PublicVeterinarySummaryResult(
            share.id.value, share.label, share.expiresAt,
            buildSummary(
                VeterinarySummaryQuery(share.petId, share.scope.from, share.scope.to), share.householdId,
                Disclosure(share.scope.includeNotes, share.scope.includeCosts, share.scope.includeDocuments),
            ),
        )
    }

    override fun sharedAttachmentUrl(command: SharedAttachmentUrlCommand): String = transaction.execute {
        val share = activeShare(command.token)
        if (!share.scope.includeDocuments) throw NotFoundException("Documento não encontrado")
        val attachment = attachments.findByMediaId(command.mediaId) ?: throw NotFoundException("Documento não encontrado")
        val record = records.findByIdAndHousehold(attachment.healthRecordId, share.householdId)
            ?.takeIf { it.petId == share.petId && inPeriod(it.occurredAt, share.scope.from, share.scope.to, share.householdId) }
            ?: throw NotFoundException("Documento não encontrado")
        val asset = media.findById(command.mediaId)?.takeIf {
            it.status == MediaStatus.READY && it.purpose == MediaPurpose.HEALTH_ATTACHMENT &&
                it.householdId == share.householdId && it.petId == record.petId && it.healthRecordId == record.id.value
        } ?: throw NotFoundException("Documento não encontrado")
        storage.presignDownload(asset.objectKey, SHARED_DOWNLOAD_TTL, asset.originalFilename)
    }

    private fun buildSummary(query: VeterinarySummaryQuery, householdId: HouseholdId, disclosure: Disclosure): VeterinarySummaryResult {
        require(!query.to.isBefore(query.from)) { "veterinary_summary_period_invalid" }
        require(Duration.between(query.from.atStartOfDay(), query.to.plusDays(1).atStartOfDay()) <= MAX_PERIOD) {
            "veterinary_summary_period_too_large"
        }
        val pet = pets.findByIdAndHousehold(query.petId, householdId) ?: throw NotFoundException("Resumo não encontrado")
        val zone = zoneFor(householdId)
        val fromInstant = query.from.atStartOfDay(zone).toInstant()
        val toInstant = query.to.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = HealthRecordFilter(query.petId, fromInstant, toInstant, null)
        val recordCount = records.countByHousehold(householdId, filter)
        val recordItems = records.searchByHousehold(householdId, filter, 0, MAX_RECORDS)
        val measurementItems = measurements.listByHousehold(householdId, query.petId, null, fromInstant, toInstant, MAX_MEASUREMENTS)
        val careItems = occurrences.searchByHousehold(
            householdId,
            CareOccurrenceFilter(fromInstant, toInstant, query.petId),
            0, MAX_OCCURRENCES,
        )
        val now = clock.now(zone).toInstant()
        val completed = careItems.count { it.status == CareOccurrenceStatus.COMPLETED }
        val overdue = careItems.count { it.status == CareOccurrenceStatus.SCHEDULED && it.dueAt.isBefore(now) }
        val upcoming = careItems.count { it.status == CareOccurrenceStatus.SCHEDULED && !it.dueAt.isBefore(now) }
        val cancelled = careItems.count { it.status == CareOccurrenceStatus.CANCELLED }
        val decided = completed + overdue
        val attachmentMap = if (disclosure.documents) attachments.listByRecordIds(recordItems.map { it.id }) else emptyMap()
        return VeterinarySummaryResult(
            pet.toResult(), query.from, query.to, clock.now().toInstant(),
            CareAdherenceResult(
                completed, overdue, upcoming, cancelled,
                decided.takeIf { it > 0 }?.let { BigDecimal(completed * 100).divide(BigDecimal(it), 1, RoundingMode.HALF_UP) },
            ),
            recordItems.map { it.toResult(disclosure) },
            measurementItems.map { VeterinaryMeasurementResult(it.id.value, it.type, it.value, it.unit, it.measuredAt) },
            if (disclosure.documents) recordItems.flatMap { record ->
                attachmentMap[record.id].orEmpty().map { item ->
                    VeterinaryDocumentResult(item.mediaAssetId, record.id.value, item.originalFilename, item.contentType, item.sizeBytes, record.occurredAt)
                }
            } else emptyList(),
            recordCount > MAX_RECORDS || careItems.size >= MAX_OCCURRENCES || measurementItems.size >= MAX_MEASUREMENTS,
        )
    }

    private fun activeShare(rawToken: String): VeterinaryShare {
        require(rawToken.length in 32..128) { "veterinary_share_token_invalid" }
        val share = shares.findActiveByHashForUpdate(hash(rawToken)) ?: throw NotFoundException("Link inválido ou expirado")
        val now = clock.now().toInstant()
        if (share.revokedAt != null || !share.expiresAt.isAfter(now)) throw NotFoundException("Link inválido ou expirado")
        return share
    }

    private fun inPeriod(value: Instant, from: java.time.LocalDate, to: java.time.LocalDate, householdId: HouseholdId): Boolean {
        val zone = zoneFor(householdId)
        return !value.isBefore(from.atStartOfDay(zone).toInstant()) && value.isBefore(to.plusDays(1).atStartOfDay(zone).toInstant())
    }
    private fun zoneFor(householdId: HouseholdId) =
        households?.findById(householdId)?.timezone ?: HouseholdTimezone.parse(null)
    private fun Pet.toResult() = VeterinaryPetResult(id!!.value, name, species, breed, birthdate)
    private fun HealthRecord.toResult(disclosure: Disclosure) = VeterinaryRecordResult(
        id.value, type, occurredAt, title, notes.takeIf { disclosure.notes }, productName, dosage, batchNumber,
        professionalName, clinicName, costAmount.takeIf { disclosure.costs }, currency.takeIf { disclosure.costs },
    )
    private fun VeterinaryShare.toResult() = VeterinaryShareResult(
        id.value, version, petId.value, label, scope.from, scope.to, scope.includeNotes, scope.includeCosts,
        scope.includeDocuments, expiresAt, revokedAt, createdAt, lastAccessedAt, accessCount,
    )
    private fun requirePermission(access: HouseholdAccess, permission: HouseholdPermission) {
        if (!access.can(permission)) throw ForbiddenException("Seu papel nesta família não permite esta ação")
    }
    private fun token() = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Disclosure(val notes: Boolean, val costs: Boolean, val documents: Boolean) {
        companion object { val ALL = Disclosure(true, true, true) }
    }

    companion object {
        private val MAX_PERIOD = Duration.ofDays(366)
        private val ALLOWED_EXPIRY_HOURS = setOf(1L, 24L, 72L, 168L, 720L)
        private val SHARED_DOWNLOAD_TTL = Duration.ofMinutes(5)
        private const val MAX_ACTIVE_SHARES = 20
        private const val MAX_RECORDS = 500
        private const val MAX_MEASUREMENTS = 500
        private const val MAX_OCCURRENCES = 2_000
    }
}
