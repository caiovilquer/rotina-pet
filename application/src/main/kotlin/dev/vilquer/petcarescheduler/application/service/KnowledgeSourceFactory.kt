package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.core.domain.health.HealthRecord
import dev.vilquer.petcarescheduler.core.domain.media.MediaAsset
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSource
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object KnowledgeSourceFactory {
    const val FAKE_EMBEDDING_SIGNATURE = "fake/local-hash-embedding-v2/512"
    const val CHUNKER_VERSION = "semantic-chunker-v1"
    const val RECORD_EXTRACTOR_VERSION = "health-record-v1"
    const val DOCUMENT_EXTRACTOR_VERSION = "pdfbox-v1"

    fun healthRecord(record: HealthRecord, at: Instant, embeddingSignature: String = FAKE_EMBEDDING_SIGNATURE) = KnowledgeSource(
        id = stableId(record.householdId.value, KnowledgeSourceType.HEALTH_RECORD, record.id.value),
        householdId = record.householdId,
        petId = record.petId,
        type = KnowledgeSourceType.HEALTH_RECORD,
        resourceId = record.id.value,
        resourceVersion = (record.version ?: 0).toString(),
        title = record.title,
        checksum = sha256(
            listOf(
                record.type.name, record.occurredAt.toString(), record.title, record.notes,
                record.productName, record.dosage, record.batchNumber, record.professionalName, record.clinicName,
            ).joinToString("\u0000") { it.orEmpty() },
        ),
        status = KnowledgeSourceStatus.PENDING,
        extractorVersion = RECORD_EXTRACTOR_VERSION,
        chunkerVersion = CHUNKER_VERSION,
        embeddingModel = embeddingSignature,
        createdAt = at,
        updatedAt = at,
    )

    fun healthAttachment(asset: MediaAsset, at: Instant, embeddingSignature: String = FAKE_EMBEDDING_SIGNATURE) = KnowledgeSource(
        id = stableId(requireNotNull(asset.householdId).value, KnowledgeSourceType.HEALTH_ATTACHMENT, asset.id),
        householdId = requireNotNull(asset.householdId),
        petId = requireNotNull(asset.petId),
        type = KnowledgeSourceType.HEALTH_ATTACHMENT,
        resourceId = asset.id,
        resourceVersion = (asset.version ?: 0).toString(),
        title = asset.originalFilename,
        checksum = asset.checksumSha256,
        status = KnowledgeSourceStatus.PENDING,
        extractorVersion = DOCUMENT_EXTRACTOR_VERSION,
        chunkerVersion = CHUNKER_VERSION,
        embeddingModel = embeddingSignature,
        createdAt = at,
        updatedAt = at,
    )

    fun stableId(householdId: UUID, type: KnowledgeSourceType, resourceId: UUID): UUID =
        UUID.nameUUIDFromBytes("knowledge:$householdId:${type.name}:$resourceId".toByteArray(StandardCharsets.UTF_8))

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
