package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordId
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdPermission
import dev.vilquer.petcarescheduler.usecase.command.ReindexKnowledgeSourceCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.DocumentTextExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeChunk
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOperation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSource
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.MediaAssetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ObjectStoragePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.KnowledgeIndexUseCase
import dev.vilquer.petcarescheduler.usecase.result.KnowledgeSourceResult
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class KnowledgeIndexSettings(
    val enabled: Boolean = false,
    val batchSize: Int = 10,
    val maxAttempts: Int = 5,
    val maxDocumentBytes: Long = 10L * 1024 * 1024,
) {
    init {
        require(batchSize in 1..100) { "knowledge_batch_size_invalid" }
        require(maxAttempts in 1..20) { "knowledge_max_attempts_invalid" }
        require(maxDocumentBytes in 1..25L * 1024 * 1024) { "knowledge_document_limit_invalid" }
    }
}

class KnowledgeIndexingService(
    private val sources: KnowledgeSourceRepositoryPort,
    private val outbox: KnowledgeIndexOutboxPort,
    private val records: HealthRecordRepositoryPort,
    private val media: MediaAssetRepositoryPort,
    private val storage: ObjectStoragePort,
    private val extractor: DocumentTextExtractorPort,
    private val embeddings: EmbeddingPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val settings: KnowledgeIndexSettings,
) : KnowledgeIndexUseCase {

    override fun processBatch() {
        if (!settings.enabled) return
        val now = clock.now().toInstant()
        scheduleConfigurationReindex(now)
        outbox.claimBatch(settings.batchSize, now).forEach { job ->
            val source = sources.findById(job.sourceId)
            if (source == null || source.status == KnowledgeSourceStatus.DELETED || job.operation == KnowledgeIndexOperation.DELETE) {
                transaction.execute {
                    sources.deleteChunks(job.sourceId)
                    outbox.markCompleted(job.id, clock.now().toInstant())
                }
                return@forEach
            }
            runCatching { index(source) }.onSuccess {
                outbox.markCompleted(job.id, clock.now().toInstant())
            }.onFailure { failure ->
                val errorCode = normalizeError(failure)
                val at = clock.now().toInstant()
                if (job.attempts >= settings.maxAttempts) {
                    transaction.execute {
                        sources.markFailed(source.id, errorCode, at)
                        outbox.markDead(job.id, errorCode, at)
                    }
                } else {
                    transaction.execute {
                        sources.markFailed(source.id, errorCode, at)
                        outbox.markRetry(job.id, errorCode, at.plus(backoff(job.attempts)), at)
                    }
                }
            }
        }
    }

    override fun listSources(petId: dev.vilquer.petcarescheduler.core.domain.entity.PetId, access: HouseholdAccess): List<KnowledgeSourceResult> {
        requireView(access)
        return sources.listByPet(access.householdId, petId).map(::result)
    }

    override fun reindex(command: ReindexKnowledgeSourceCommand, access: HouseholdAccess): KnowledgeSourceResult {
        requireRecordHealth(access)
        val source = sources.findByIdAndHousehold(command.sourceId, access.householdId)
            ?: throw NotFoundException("Fonte não encontrada")
        if (source.status == KnowledgeSourceStatus.DELETED) throw NotFoundException("Fonte não encontrada")
        val at = clock.now(access.zoneId).toInstant()
        transaction.execute {
            sources.markPending(source.id, at)
            outbox.enqueue(source.id, KnowledgeIndexOperation.REINDEX, "reindex:${source.id}:${UUID.randomUUID()}", at)
        }
        return result(source.copy(status = KnowledgeSourceStatus.PENDING, errorCode = null, updatedAt = at))
    }

    private fun index(source: KnowledgeSource) {
        val at = clock.now().toInstant()
        sources.markIndexing(source.id, at)
        val sections = when (source.type) {
            KnowledgeSourceType.HEALTH_RECORD -> recordSections(source)
            KnowledgeSourceType.HEALTH_ATTACHMENT -> attachmentSections(source)
            else -> throw IllegalArgumentException("knowledge_source_type_unsupported")
        }
        val pieces = sections.flatMap { section -> chunk(section.text).map { piece -> Triple(section.page, piece.first, piece.second) } }
        require(pieces.isNotEmpty()) { "knowledge_source_empty" }
        val effectiveSource = source.copy(
            embeddingModel = embeddings.signature,
            chunkerVersion = KnowledgeSourceFactory.CHUNKER_VERSION,
            extractorVersion = currentExtractorVersion(source),
        )
        val vectors = embeddings.embed(
            pieces.map { it.third },
            dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext(source.householdId),
        )
        require(vectors.size == pieces.size && vectors.all { it.size == embeddings.dimension }) { "embedding_output_invalid" }
        val chunks = pieces.mapIndexed { index, (page, offset, text) ->
            KnowledgeChunk(
                id = UUID.nameUUIDFromBytes("${source.id}:$index:${KnowledgeSourceFactory.sha256(text)}".toByteArray()),
                sourceId = source.id,
                chunkIndex = index,
                text = text,
                embedding = vectors[index],
                page = page,
                startOffset = offset,
                endOffset = offset + text.length,
                contentHash = KnowledgeSourceFactory.sha256(text),
                embeddingModel = embeddings.signature,
            )
        }
        transaction.execute {
            val current = sources.findById(source.id) ?: throw NotFoundException("Fonte não encontrada")
            require(current.checksum == source.checksum) { "knowledge_source_changed_during_indexing" }
            sources.replaceChunksAndMarkReady(effectiveSource.copy(checksum = current.checksum), chunks, clock.now().toInstant())
        }
    }

    private fun recordSections(source: KnowledgeSource): List<Section> {
        val record = records.findByIdAndHousehold(HealthRecordId(source.resourceId), source.householdId)
            ?: throw NotFoundException("Registro de origem não encontrado")
        require(record.petId == source.petId) { "knowledge_source_pet_mismatch" }
        val text = buildList {
            add("Título: ${record.title}")
            add("Tipo: ${record.type.name}")
            add("Data: ${record.occurredAt}")
            record.notes?.let { add("Notas: $it") }
            record.productName?.let { add("Produto informado: $it") }
            record.dosage?.let { add("Dose registrada: $it") }
            record.batchNumber?.let { add("Lote: $it") }
            record.professionalName?.let { add("Profissional: $it") }
            record.clinicName?.let { add("Clínica: $it") }
        }.joinToString("\n")
        return listOf(Section(null, text))
    }

    private fun attachmentSections(source: KnowledgeSource): List<Section> {
        val asset = media.findById(source.resourceId)?.takeIf { it.status == MediaStatus.READY }
            ?: throw NotFoundException("Documento de origem não encontrado")
        require(asset.householdId == source.householdId && asset.petId == source.petId) { "knowledge_source_scope_mismatch" }
        require(asset.contentType == "application/pdf") { "knowledge_document_type_unsupported" }
        val bytes = storage.readObject(asset.objectKey, settings.maxDocumentBytes + 1)
        require(bytes.size.toLong() <= settings.maxDocumentBytes) { "knowledge_document_too_large" }
        return extractor.extract(bytes, asset.contentType).pages.map { Section(it.page, it.text) }
    }

    private fun scheduleConfigurationReindex(at: Instant) {
        val stale = sources.findConfigurationMismatches(
            embeddings.signature,
            KnowledgeSourceFactory.CHUNKER_VERSION,
            KnowledgeSourceFactory.RECORD_EXTRACTOR_VERSION,
            extractor.version,
            settings.batchSize,
        )
        stale.forEach { source ->
            transaction.execute {
                sources.markPending(source.id, at)
                outbox.enqueue(
                    source.id,
                    KnowledgeIndexOperation.REINDEX,
                    "configuration:${source.id}:${currentExtractorVersion(source)}:${KnowledgeSourceFactory.CHUNKER_VERSION}:${embeddings.signature}",
                    at,
                )
            }
        }
    }

    private fun currentExtractorVersion(source: KnowledgeSource): String = when (source.type) {
        KnowledgeSourceType.HEALTH_RECORD -> KnowledgeSourceFactory.RECORD_EXTRACTOR_VERSION
        KnowledgeSourceType.HEALTH_ATTACHMENT -> extractor.version
        else -> source.extractorVersion
    }

    private fun chunk(text: String): List<Pair<Int, String>> {
        val clean = text.replace('\u0000', ' ').replace(Regex("[ \\t]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= MAX_CHUNK_CHARS) return listOf(0 to clean)
        val chunks = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < clean.length) {
            var end = (start + MAX_CHUNK_CHARS).coerceAtMost(clean.length)
            if (end < clean.length) {
                val boundary = clean.lastIndexOfAny(charArrayOf('\n', '.', ';'), end - 1).takeIf { it > start + MIN_CHUNK_CHARS }
                if (boundary != null) end = boundary + 1
            }
            val value = clean.substring(start, end).trim()
            if (value.isNotEmpty()) chunks += start to value
            if (end >= clean.length) break
            start = (end - CHUNK_OVERLAP_CHARS).coerceAtLeast(start + 1)
        }
        return chunks
    }

    private fun result(source: KnowledgeSource) = KnowledgeSourceResult(
        source.id, source.type, source.resourceId, source.title, source.status, source.errorCode, source.updatedAt,
    )

    private fun requireView(access: HouseholdAccess) {
        if (!access.can(HouseholdPermission.VIEW)) throw ForbiddenException("Seu papel nesta família não permite consultar fontes")
    }

    private fun requireRecordHealth(access: HouseholdAccess) {
        if (!access.can(HouseholdPermission.RECORD_HEALTH)) throw ForbiddenException("Seu papel nesta família não permite reindexar fontes")
    }

    private fun normalizeError(failure: Throwable): String = when (failure) {
        is NotFoundException -> "SOURCE_NOT_FOUND"
        is IllegalArgumentException -> failure.message?.uppercase()?.replace(Regex("[^A-Z0-9_]+"), "_")?.take(80) ?: "INDEX_INVALID_SOURCE"
        else -> "INDEX_PROVIDER_ERROR"
    }

    private fun backoff(attempts: Int): Duration = Duration.ofSeconds((30L shl (attempts - 1).coerceIn(0, 6)))

    private data class Section(val page: Int?, val text: String)

    companion object {
        private const val MAX_CHUNK_CHARS = 2_800
        private const val MIN_CHUNK_CHARS = 1_400
        private const val CHUNK_OVERLAP_CHARS = 240
    }
}
