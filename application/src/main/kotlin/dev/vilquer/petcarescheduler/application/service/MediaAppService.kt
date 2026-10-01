package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordAttachment
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordId
import dev.vilquer.petcarescheduler.core.domain.media.MediaAsset
import dev.vilquer.petcarescheduler.core.domain.media.MediaPurpose
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.usecase.command.CompleteMediaUploadCommand
import dev.vilquer.petcarescheduler.usecase.command.DeleteMediaCommand
import dev.vilquer.petcarescheduler.usecase.command.InitiateMediaUploadCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.MediaAssetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordAttachmentRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ObjectStoragePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOperation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.MediaMaintenanceUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.MediaUploadUseCase
import dev.vilquer.petcarescheduler.usecase.result.MediaAssetResult
import dev.vilquer.petcarescheduler.usecase.result.MediaUploadInitiatedResult
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

class MediaAppService(
    private val media: MediaAssetRepositoryPort,
    private val storage: ObjectStoragePort,
    private val pets: PetRepositoryPort,
    private val tutors: TutorRepositoryPort,
    private val healthRecords: HealthRecordRepositoryPort,
    private val healthAttachments: HealthRecordAttachmentRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: Clock = Clock.systemUTC(),
    private val knowledgeSources: KnowledgeSourceRepositoryPort? = null,
    private val knowledgeOutbox: KnowledgeIndexOutboxPort? = null,
    private val embeddingSignature: String = KnowledgeSourceFactory.FAKE_EMBEDDING_SIGNATURE,
) : MediaUploadUseCase, MediaMaintenanceUseCase {

    fun initiate(command: InitiateMediaUploadCommand, tutorId: TutorId): MediaUploadInitiatedResult {
        if (command.purpose == MediaPurpose.PET_PHOTO) {
            val petId = PetId(requireNotNull(command.targetId))
            if (!pets.existsForTutor(petId, tutorId)) throw ForbiddenException("Pet não pertence ao tutor autenticado")
        }
        return initiate(command, legacyAccess(tutorId))
    }
    fun complete(command: CompleteMediaUploadCommand, tutorId: TutorId) = complete(command, legacyAccess(tutorId))
    fun delete(command: DeleteMediaCommand, tutorId: TutorId) = delete(command, legacyAccess(tutorId))
    fun downloadUrl(mediaId: UUID, tutorId: TutorId): String {
        val asset = media.findById(mediaId) ?: throw NotFoundException("Mídia não encontrada")
        if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT && asset.tutorId != tutorId) throw NotFoundException("Mídia não encontrada")
        return downloadUrl(mediaId, legacyAccess(tutorId))
    }

    private fun legacyAccess(tutorId: TutorId) = HouseholdAccess(
        HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000001")), tutorId, HouseholdRole.OWNER,
    )

    override fun initiate(command: InitiateMediaUploadCommand, access: HouseholdAccess): MediaUploadInitiatedResult {
        validateRequest(command)
        val tutorId = access.actorTutorId
        var healthRecordId: UUID? = null
        val petId = when (command.purpose) {
            MediaPurpose.PET_PHOTO -> PetId(requireNotNull(command.targetId) { "media_target_id_required" }).also {
                requirePermission(access, HouseholdPermission.MANAGE_PETS)
                if (!pets.existsForHousehold(it, access.householdId)) throw NotFoundException("Pet não encontrado")
            }
            MediaPurpose.TUTOR_AVATAR -> {
                if (command.targetId != tutorId.value) throw ForbiddenException("Avatar não pertence ao tutor autenticado")
                if (tutors.findById(tutorId) == null) throw NotFoundException("Tutor não encontrado")
                null
            }
            MediaPurpose.HEALTH_ATTACHMENT -> {
                requirePermission(access, HouseholdPermission.RECORD_HEALTH)
                val recordId = HealthRecordId(requireNotNull(command.targetUuid) { "media_target_uuid_required" })
                val record = healthRecords.findByIdAndHousehold(recordId, access.householdId)
                    ?: throw NotFoundException("Registro de saúde não encontrado")
                healthRecordId = record.id.value
                record.petId
            }
        }
        val now = Instant.now(clock)
        val id = UUID.randomUUID()
        val extensions = if (command.purpose == MediaPurpose.HEALTH_ATTACHMENT) {
            ATTACHMENT_EXTENSIONS
        } else {
            IMAGE_EXTENSIONS
        }
        val extension = extensions.getValue(command.contentType)
        val category = command.purpose.name.lowercase()
        val asset = MediaAsset(
            id = id,
            tutorId = tutorId,
            householdId = access.householdId.takeUnless { command.purpose == MediaPurpose.TUTOR_AVATAR },
            petId = petId,
            healthRecordId = healthRecordId,
            purpose = command.purpose,
            originalFilename = sanitizeFilename(command.filename),
            contentType = command.contentType,
            expectedSize = command.sizeBytes,
            checksumSha256 = command.checksumSha256.lowercase(),
            stagingKey = "staging/${tutorId.value}/$id",
            objectKey = if (command.purpose == MediaPurpose.TUTOR_AVATAR) "media/tutors/${tutorId.value}/$category/$id.$extension"
                else "media/households/${access.householdId.value}/$category/$id.$extension",
            createdAt = now,
        )
        media.save(asset)
        val signed = storage.presignUpload(asset.stagingKey, asset.contentType, asset.checksumSha256, UPLOAD_TTL)
        return MediaUploadInitiatedResult(id, signed.url, signed.headers, now.plus(UPLOAD_TTL))
    }

    override fun complete(command: CompleteMediaUploadCommand, access: HouseholdAccess): MediaAssetResult = transaction.execute {
        val tutorId = access.actorTutorId
        val asset = ownedPendingForUpdate(command.uploadId, access)
        val recordId = asset.healthRecordId?.let(::HealthRecordId)
        if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT) {
            requirePermission(access, HouseholdPermission.RECORD_HEALTH)
            val record = healthRecords.findByIdAndHouseholdForUpdate(recordId!!, access.householdId)
                ?: throw NotFoundException("Registro de saúde não encontrado")
            require(record.petId == asset.petId) { "health_attachment_pet_mismatch" }
            require(healthAttachments.countByRecord(recordId) < MAX_ATTACHMENTS_PER_RECORD) {
                "health_attachment_limit_reached"
            }
        }
        val maxBytes = if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT) MAX_ATTACHMENT_BYTES else MAX_IMAGE_BYTES
        val bytes = storage.readObject(asset.stagingKey, maxBytes + 1)
        validateUploadedContent(asset, bytes)
        storage.promote(asset.stagingKey, asset.objectKey, asset.contentType)
        val now = Instant.now(clock)

        when (asset.purpose) {
                MediaPurpose.PET_PHOTO -> {
                    requirePermission(access, HouseholdPermission.MANAGE_PETS)
                    val pet = pets.findByIdAndHousehold(asset.petId!!, access.householdId)
                        ?: throw NotFoundException("Pet não encontrado")
                    pet.photoAssetId?.takeIf { it != asset.id }?.let { oldId ->
                        media.findById(oldId)?.let { media.save(it.copy(status = MediaStatus.PENDING_DELETE)) }
                    }
                    pets.save(pet.copy(photoAssetId = asset.id, photoUrl = null))
                }
                MediaPurpose.TUTOR_AVATAR -> {
                    val tutor = tutors.findById(tutorId) ?: throw NotFoundException("Tutor não encontrado")
                    tutor.avatarAssetId?.takeIf { it != asset.id }?.let { oldId ->
                        media.findById(oldId)?.let { media.save(it.copy(status = MediaStatus.PENDING_DELETE)) }
                    }
                    tutors.save(tutor.copy(avatarAssetId = asset.id, avatar = null))
                }
                MediaPurpose.HEALTH_ATTACHMENT -> healthAttachments.save(
                    HealthRecordAttachment(
                        healthRecordId = recordId!!,
                        mediaAssetId = asset.id,
                        createdAt = now,
                    ),
                )
        }
        val ready = media.save(asset.copy(status = MediaStatus.READY, readyAt = now))
        if (ready.purpose == MediaPurpose.HEALTH_ATTACHMENT && ready.contentType == "application/pdf") {
            prepareKnowledge(ready, now)
        }
        MediaAssetResult(asset.id, contentPath(asset.id, asset.purpose))
    }

    override fun delete(command: DeleteMediaCommand, access: HouseholdAccess) {
        transaction.execute {
            val tutorId = access.actorTutorId
            val asset = media.findByIdForUpdate(command.mediaId) ?: return@execute
            if (asset.purpose == MediaPurpose.TUTOR_AVATAR) {
                if (asset.tutorId != tutorId) throw NotFoundException("Mídia não encontrada")
            } else if (asset.householdId != access.householdId) throw NotFoundException("Mídia não encontrada")
            if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT) {
                requirePermission(access, HouseholdPermission.RECORD_HEALTH)
                val recordId = asset.healthRecordId?.let(::HealthRecordId)
                    ?: throw NotFoundException("Mídia não encontrada")
                val record = healthRecords.findByIdAndHouseholdForUpdate(recordId, access.householdId)
                    ?: throw NotFoundException("Mídia não encontrada")
                if (record.petId != asset.petId) throw NotFoundException("Mídia não encontrada")
            }
            when (asset.purpose) {
                MediaPurpose.PET_PHOTO -> asset.petId?.let { id ->
                    requirePermission(access, HouseholdPermission.MANAGE_PETS)
                    pets.findByIdAndHousehold(id, access.householdId)?.takeIf { it.photoAssetId == asset.id }
                        ?.let { pets.save(it.copy(photoAssetId = null)) }
                }
                MediaPurpose.TUTOR_AVATAR -> tutors.findById(tutorId)?.takeIf { it.avatarAssetId == asset.id }
                    ?.let { tutors.save(it.copy(avatarAssetId = null)) }
                MediaPurpose.HEALTH_ATTACHMENT -> healthAttachments.deleteByMediaId(asset.id)
            }
            if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT) deleteKnowledge(asset, Instant.now(clock))
            media.save(asset.copy(status = MediaStatus.PENDING_DELETE))
        }
    }

    override fun downloadUrl(mediaId: UUID, access: HouseholdAccess?): String {
        val asset = media.findById(mediaId)?.takeIf { it.status == MediaStatus.READY }
            ?: throw NotFoundException("Mídia não encontrada")
        if (asset.purpose == MediaPurpose.HEALTH_ATTACHMENT) {
            val owner = access ?: throw NotFoundException("Mídia não encontrada")
            requirePermission(owner, HouseholdPermission.VIEW)
            if (asset.householdId != owner.householdId) throw NotFoundException("Mídia não encontrada")
            val recordId = asset.healthRecordId?.let(::HealthRecordId)
                ?: throw NotFoundException("Mídia não encontrada")
            val record = healthRecords.findByIdAndHousehold(recordId, owner.householdId)
                ?: throw NotFoundException("Mídia não encontrada")
            if (record.petId != asset.petId) throw NotFoundException("Mídia não encontrada")
        }
        return storage.presignDownload(
            asset.objectKey,
            DOWNLOAD_TTL,
            asset.originalFilename.takeIf { asset.purpose == MediaPurpose.HEALTH_ATTACHMENT },
        )
    }

    override fun cleanupMedia() {
        val cutoff = Instant.now(clock).minus(PENDING_RETENTION)
        media.findCleanupCandidates(cutoff, 100).forEach { asset ->
            val removedFromStorage = runCatching {
                storage.delete(asset.stagingKey)
                storage.delete(asset.objectKey)
            }.isSuccess
            if (removedFromStorage) media.delete(asset.id)
        }
    }

    private fun ownedPendingForUpdate(id: UUID, access: HouseholdAccess): MediaAsset {
        val asset = media.findByIdForUpdate(id) ?: throw NotFoundException("Upload não encontrado")
        if (asset.tutorId != access.actorTutorId) throw NotFoundException("Upload não encontrado")
        if (asset.purpose != MediaPurpose.TUTOR_AVATAR && asset.householdId != access.householdId) throw NotFoundException("Upload não encontrado")
        require(asset.status == MediaStatus.PENDING) { "upload_not_pending" }
        require(asset.createdAt.plus(PENDING_RETENTION).isAfter(Instant.now(clock))) { "upload_expired" }
        return asset
    }

    private fun requirePermission(access: HouseholdAccess, permission: HouseholdPermission) {
        if (!access.can(permission)) throw ForbiddenException("Seu papel nesta família não permite esta ação")
    }

    private fun validateRequest(command: InitiateMediaUploadCommand) {
        val allowedTypes = if (command.purpose == MediaPurpose.HEALTH_ATTACHMENT) ATTACHMENT_EXTENSIONS else IMAGE_EXTENSIONS
        val maxBytes = if (command.purpose == MediaPurpose.HEALTH_ATTACHMENT) MAX_ATTACHMENT_BYTES else MAX_IMAGE_BYTES
        require(command.contentType in allowedTypes) { "unsupported_media_type" }
        require(command.sizeBytes in 1..maxBytes) { "media_size_invalid" }
        require(command.filename.isNotBlank() && command.filename.length <= 180) { "filename_invalid" }
        require(command.checksumSha256.lowercase().matches(SHA256_REGEX)) { "checksum_invalid" }
    }

    private fun validateUploadedContent(asset: MediaAsset, bytes: ByteArray) {
        require(bytes.size.toLong() == asset.expectedSize) { "uploaded_size_mismatch" }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(MessageDigest.isEqual(actualHash.toByteArray(), asset.checksumSha256.toByteArray())) {
            "uploaded_checksum_mismatch"
        }
        require(hasExpectedSignature(bytes, asset.contentType)) { "invalid_media_signature" }
        if (asset.contentType == "application/pdf") {
            require(hasPdfTrailer(bytes)) { "invalid_pdf_content" }
            return
        }
        ImageIO.createImageInputStream(bytes.inputStream()).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: throw IllegalArgumentException("invalid_image_content")
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "image_dimensions_invalid" }
                require(width.toLong() * height.toLong() <= MAX_PIXELS) { "image_pixels_invalid" }
                reader.read(0) ?: throw IllegalArgumentException("invalid_image_content")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun hasExpectedSignature(bytes: ByteArray, contentType: String): Boolean = when (contentType) {
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "application/pdf" -> bytes.size >= 8 && bytes.copyOfRange(0, 7).toString(Charsets.US_ASCII).startsWith("%PDF-1.")
        else -> false
    }

    private fun hasPdfTrailer(bytes: ByteArray): Boolean =
        bytes.takeLast(minOf(bytes.size, 1_024)).toByteArray().toString(Charsets.US_ASCII).contains("%%EOF")

    private fun sanitizeFilename(filename: String): String = filename
        .replace(Regex("[\\r\\n\\u0000-\\u001f\\u007f]"), "")
        .substringAfterLast('/').substringAfterLast('\\').take(180)

    private fun contentPath(id: UUID, purpose: MediaPurpose) = if (purpose == MediaPurpose.HEALTH_ATTACHMENT) {
        "/api/v1/health-attachments/$id/download-url"
    } else {
        "/api/v1/media/$id/content"
    }

    private fun prepareKnowledge(asset: MediaAsset, at: Instant) {
        val sourceRepository = knowledgeSources ?: return
        val outboxRepository = knowledgeOutbox ?: return
        val prepared = sourceRepository.prepare(KnowledgeSourceFactory.healthAttachment(asset, at, embeddingSignature))
        if (prepared.changed) {
            outboxRepository.enqueue(
                prepared.source.id,
                KnowledgeIndexOperation.UPSERT,
                "upsert:${prepared.source.id}:${prepared.source.checksum}",
                at,
            )
        }
    }

    private fun deleteKnowledge(asset: MediaAsset, at: Instant) {
        val householdId = asset.householdId ?: return
        val sourceRepository = knowledgeSources ?: return
        val outboxRepository = knowledgeOutbox ?: return
        sourceRepository.markDeleted(householdId, KnowledgeSourceType.HEALTH_ATTACHMENT, asset.id, at)?.let { source ->
            outboxRepository.enqueue(source.id, KnowledgeIndexOperation.DELETE, "delete:${source.id}:${source.updatedAt}", at)
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
        const val MAX_ATTACHMENTS_PER_RECORD = 5L
        private const val MAX_DIMENSION = 6000
        private const val MAX_PIXELS = 25_000_000L
        private val IMAGE_EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png")
        private val ATTACHMENT_EXTENSIONS = IMAGE_EXTENSIONS + ("application/pdf" to "pdf")
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val UPLOAD_TTL = Duration.ofMinutes(3)
        private val DOWNLOAD_TTL = Duration.ofMinutes(15)
        private val PENDING_RETENTION = Duration.ofMinutes(15)
    }
}
