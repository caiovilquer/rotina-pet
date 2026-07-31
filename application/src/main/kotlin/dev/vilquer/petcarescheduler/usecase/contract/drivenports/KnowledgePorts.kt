package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.time.Instant
import java.util.UUID

enum class KnowledgeSourceType { HEALTH_RECORD, HEALTH_MEASUREMENT, HEALTH_ATTACHMENT, CARE_PLAN, VETERINARY_SUMMARY_NOTE }
enum class KnowledgeSourceStatus { PENDING, INDEXING, READY, FAILED, STALE, DELETED }
enum class KnowledgeIndexOperation { UPSERT, DELETE, REINDEX }

data class KnowledgeSource(
    val id: UUID,
    val householdId: HouseholdId,
    val petId: PetId,
    val type: KnowledgeSourceType,
    val resourceId: UUID,
    val resourceVersion: String,
    val title: String,
    val checksum: String,
    val language: String = "pt-BR",
    val status: KnowledgeSourceStatus = KnowledgeSourceStatus.PENDING,
    val extractorVersion: String,
    val chunkerVersion: String,
    val embeddingModel: String,
    val errorCode: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class KnowledgeSourcePreparation(val source: KnowledgeSource, val changed: Boolean)

data class KnowledgeIndexJob(
    val id: UUID,
    val sourceId: UUID,
    val operation: KnowledgeIndexOperation,
    val attempts: Int,
)

data class KnowledgeChunk(
    val id: UUID,
    val sourceId: UUID,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray,
    val page: Int?,
    val startOffset: Int,
    val endOffset: Int,
    val contentHash: String,
    val embeddingModel: String,
)

data class SemanticSearchRequest(
    val householdId: HouseholdId,
    val petId: PetId,
    val query: String,
    val embedding: FloatArray,
    val limit: Int,
    val minimumSemanticScore: Double = 0.08,
)

data class RetrievedKnowledge(
    val chunkId: UUID,
    val sourceId: UUID,
    val sourceType: KnowledgeSourceType,
    val resourceId: UUID,
    val title: String,
    val text: String,
    val page: Int?,
    val lexicalScore: Double,
    val semanticScore: Double,
    val combinedScore: Double,
)

interface KnowledgeSourceRepositoryPort {
    fun prepare(source: KnowledgeSource): KnowledgeSourcePreparation
    fun findById(id: UUID): KnowledgeSource?
    fun findByIdAndHousehold(id: UUID, householdId: HouseholdId): KnowledgeSource?
    fun findByResource(householdId: HouseholdId, type: KnowledgeSourceType, resourceId: UUID): KnowledgeSource?
    fun listByPet(householdId: HouseholdId, petId: PetId): List<KnowledgeSource>
    fun findConfigurationMismatches(
        embeddingModel: String,
        chunkerVersion: String,
        healthRecordExtractorVersion: String,
        healthAttachmentExtractorVersion: String,
        limit: Int,
    ): List<KnowledgeSource> = emptyList()
    fun markIndexing(id: UUID, at: Instant)
    fun markPending(id: UUID, at: Instant)
    fun replaceChunksAndMarkReady(source: KnowledgeSource, chunks: List<KnowledgeChunk>, at: Instant)
    fun markFailed(id: UUID, errorCode: String, at: Instant)
    fun markDeleted(householdId: HouseholdId, type: KnowledgeSourceType, resourceId: UUID, at: Instant): KnowledgeSource?
    fun deleteChunks(sourceId: UUID)
}

interface KnowledgeIndexOutboxPort {
    fun enqueue(sourceId: UUID, operation: KnowledgeIndexOperation, dedupeKey: String, at: Instant)
    fun claimBatch(limit: Int, at: Instant): List<KnowledgeIndexJob>
    fun markCompleted(id: UUID, at: Instant)
    fun markRetry(id: UUID, errorCode: String, nextAttemptAt: Instant, at: Instant)
    fun markDead(id: UUID, errorCode: String, at: Instant)
}

interface SemanticSearchPort {
    fun search(request: SemanticSearchRequest): List<RetrievedKnowledge>
}

interface EmbeddingPort {
    val provider: String
    val model: String
    val dimension: Int
    val signature: String get() = "$provider/$model/$dimension"
    fun embed(texts: List<String>, context: AiInvocationContext? = null): List<FloatArray>
}

class EmbeddingException(val code: String, val retryable: Boolean, cause: Throwable? = null) :
    RuntimeException(code, cause)

data class ExtractedDocumentPage(val page: Int, val text: String)
data class ExtractedDocument(val pages: List<ExtractedDocumentPage>, val extractorVersion: String)

interface DocumentTextExtractorPort {
    val version: String
    fun extract(bytes: ByteArray, contentType: String): ExtractedDocument
}

data class GroundingEvidence(
    val chunkId: UUID,
    val sourceId: UUID,
    val sourceType: KnowledgeSourceType,
    val resourceId: UUID,
    val title: String,
    val page: Int?,
    val excerpt: String,
)

data class GeneratedGroundedAnswer(
    val answer: String,
    val citedChunkIds: Set<UUID>,
    val insufficientEvidence: Boolean,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

interface GroundedAnswerGeneratorPort {
    val provider: String
    val model: String
    val promptVersion: String
    fun answer(question: String, evidence: List<GroundingEvidence>, context: AiInvocationContext): GeneratedGroundedAnswer
}

class GroundedAnswerGenerationException(val code: String, val retryable: Boolean, cause: Throwable? = null) :
    RuntimeException(code, cause)

enum class AssistantAnswerKind { STRUCTURED, RAG, REFUSAL }

data class AssistantAnswerAudit(
    val id: UUID,
    val householdId: HouseholdId,
    val actorTutorId: TutorId,
    val petId: PetId,
    val kind: AssistantAnswerKind,
    val questionHash: String,
    val insufficientEvidence: Boolean,
    val citationCount: Int,
    val provider: String?,
    val model: String?,
    val promptVersion: String?,
    val corpusVersion: String,
    val createdAt: Instant,
)

data class AssistantAnswerFeedback(
    val id: UUID = UUID.randomUUID(),
    val answerId: UUID,
    val householdId: HouseholdId,
    val actorTutorId: TutorId,
    val positive: Boolean,
    val reason: String?,
    val comment: String?,
    val createdAt: Instant,
)

interface AssistantAnswerRepositoryPort {
    fun save(answer: AssistantAnswerAudit): AssistantAnswerAudit
    fun findAnswerByIdAndHousehold(id: UUID, householdId: HouseholdId): AssistantAnswerAudit?
    fun saveFeedback(feedback: AssistantAnswerFeedback): AssistantAnswerFeedback
}
