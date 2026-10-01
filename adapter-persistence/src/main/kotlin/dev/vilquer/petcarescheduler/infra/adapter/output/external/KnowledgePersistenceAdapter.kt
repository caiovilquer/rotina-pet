package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerAudit
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerFeedback
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerKind
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeChunk
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexJob
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOperation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSource
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourcePreparation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RetrievedKnowledge
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class KnowledgePersistenceAdapter(
    private val jdbc: JdbcTemplate,
) : KnowledgeSourceRepositoryPort, KnowledgeIndexOutboxPort, SemanticSearchPort, AssistantAnswerRepositoryPort {
    private val named = NamedParameterJdbcTemplate(jdbc)

    @Transactional
    override fun prepare(source: KnowledgeSource): KnowledgeSourcePreparation {
        val current = findByResource(source.householdId, source.type, source.resourceId)
        if (current != null && current.status != KnowledgeSourceStatus.DELETED &&
            current.checksum == source.checksum && current.extractorVersion == source.extractorVersion &&
            current.chunkerVersion == source.chunkerVersion && current.embeddingModel == source.embeddingModel
        ) return KnowledgeSourcePreparation(current, false)

        named.update(
            """
            INSERT INTO knowledge_source (
                id, household_id, pet_id, type, resource_id, resource_version, title, checksum, language,
                status, extractor_version, chunker_version, embedding_model, error_code, created_at, updated_at
            ) VALUES (
                :id, :householdId, :petId, :type, :resourceId, :resourceVersion, :title, :checksum, :language,
                'PENDING', :extractorVersion, :chunkerVersion, :embeddingModel, NULL, :createdAt, :updatedAt
            )
            ON CONFLICT (household_id, type, resource_id) DO UPDATE SET
                pet_id = EXCLUDED.pet_id,
                resource_version = EXCLUDED.resource_version,
                title = EXCLUDED.title,
                checksum = EXCLUDED.checksum,
                language = EXCLUDED.language,
                status = 'PENDING',
                extractor_version = EXCLUDED.extractor_version,
                chunker_version = EXCLUDED.chunker_version,
                embedding_model = EXCLUDED.embedding_model,
                error_code = NULL,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            source.params(),
        )
        return KnowledgeSourcePreparation(
            requireNotNull(findByResource(source.householdId, source.type, source.resourceId)),
            true,
        )
    }

    override fun findById(id: UUID): KnowledgeSource? = named.query(
        "SELECT * FROM knowledge_source WHERE id = :id",
        mapOf("id" to id),
        SOURCE_MAPPER,
    ).firstOrNull()

    override fun findByIdAndHousehold(id: UUID, householdId: HouseholdId): KnowledgeSource? = named.query(
        "SELECT * FROM knowledge_source WHERE id = :id AND household_id = :householdId",
        mapOf("id" to id, "householdId" to householdId.value),
        SOURCE_MAPPER,
    ).firstOrNull()

    override fun findByResource(householdId: HouseholdId, type: KnowledgeSourceType, resourceId: UUID): KnowledgeSource? = named.query(
        "SELECT * FROM knowledge_source WHERE household_id = :householdId AND type = :type AND resource_id = :resourceId",
        mapOf("householdId" to householdId.value, "type" to type.name, "resourceId" to resourceId),
        SOURCE_MAPPER,
    ).firstOrNull()

    override fun listByPet(householdId: HouseholdId, petId: PetId): List<KnowledgeSource> = named.query(
        "SELECT * FROM knowledge_source WHERE household_id = :householdId AND pet_id = :petId AND status <> 'DELETED' ORDER BY updated_at DESC, id",
        mapOf("householdId" to householdId.value, "petId" to petId.value),
        SOURCE_MAPPER,
    )

    override fun findConfigurationMismatches(
        embeddingModel: String,
        chunkerVersion: String,
        healthRecordExtractorVersion: String,
        healthAttachmentExtractorVersion: String,
        limit: Int,
    ): List<KnowledgeSource> = named.query(
        """
        SELECT * FROM knowledge_source
        WHERE status <> 'DELETED' AND (
            embedding_model <> :embeddingModel OR
            chunker_version <> :chunkerVersion OR
            extractor_version <> CASE type
                WHEN 'HEALTH_RECORD' THEN :healthRecordExtractorVersion
                WHEN 'HEALTH_ATTACHMENT' THEN :healthAttachmentExtractorVersion
                ELSE extractor_version
            END
        )
        ORDER BY updated_at, id
        LIMIT :limit
        """.trimIndent(),
        mapOf(
            "embeddingModel" to embeddingModel,
            "chunkerVersion" to chunkerVersion,
            "healthRecordExtractorVersion" to healthRecordExtractorVersion,
            "healthAttachmentExtractorVersion" to healthAttachmentExtractorVersion,
            "limit" to limit,
        ),
        SOURCE_MAPPER,
    )

    override fun markIndexing(id: UUID, at: Instant) {
        jdbc.update("UPDATE knowledge_source SET status = 'INDEXING', error_code = NULL, updated_at = ? WHERE id = ? AND status <> 'DELETED'", Timestamp.from(at), id)
    }

    override fun markPending(id: UUID, at: Instant) {
        jdbc.update("UPDATE knowledge_source SET status = 'PENDING', error_code = NULL, updated_at = ? WHERE id = ? AND status <> 'DELETED'", Timestamp.from(at), id)
    }

    @Transactional
    override fun replaceChunksAndMarkReady(source: KnowledgeSource, chunks: List<KnowledgeChunk>, at: Instant) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE source_id = ?", source.id)
        val sql = """
            INSERT INTO knowledge_chunk (
                id, source_id, chunk_index, normalized_text, embedding, page, start_offset, end_offset,
                content_hash, embedding_model, created_at
            ) VALUES (
                :id, :sourceId, :chunkIndex, :text, CAST(:embedding AS public.vector), :page, :startOffset, :endOffset,
                :contentHash, :embeddingModel, :createdAt
            )
        """.trimIndent()
        named.batchUpdate(sql, chunks.map { chunk ->
            MapSqlParameterSource()
                .addValue("id", chunk.id)
                .addValue("sourceId", chunk.sourceId)
                .addValue("chunkIndex", chunk.chunkIndex)
                .addValue("text", chunk.text)
                .addValue("embedding", vectorLiteral(chunk.embedding))
                .addValue("page", chunk.page)
                .addValue("startOffset", chunk.startOffset)
                .addValue("endOffset", chunk.endOffset)
                .addValue("contentHash", chunk.contentHash)
                .addValue("embeddingModel", chunk.embeddingModel)
                .addValue("createdAt", Timestamp.from(at))
        }.toTypedArray())
        val changed = jdbc.update(
            """
            UPDATE knowledge_source SET status = 'READY', extractor_version = ?, chunker_version = ?,
                embedding_model = ?, error_code = NULL, updated_at = ?
            WHERE id = ? AND checksum = ? AND status <> 'DELETED'
            """.trimIndent(),
            source.extractorVersion, source.chunkerVersion, source.embeddingModel,
            Timestamp.from(at), source.id, source.checksum,
        )
        require(changed == 1) { "knowledge_source_changed_during_indexing" }
    }

    override fun markFailed(id: UUID, errorCode: String, at: Instant) {
        jdbc.update(
            "UPDATE knowledge_source SET status = 'FAILED', error_code = ?, updated_at = ? WHERE id = ? AND status <> 'DELETED'",
            errorCode.take(120), Timestamp.from(at), id,
        )
    }

    @Transactional
    override fun markDeleted(
        householdId: HouseholdId,
        type: KnowledgeSourceType,
        resourceId: UUID,
        at: Instant,
    ): KnowledgeSource? {
        val source = findByResource(householdId, type, resourceId) ?: return null
        jdbc.update("UPDATE knowledge_source SET status = 'DELETED', error_code = NULL, updated_at = ? WHERE id = ?", Timestamp.from(at), source.id)
        return source.copy(status = KnowledgeSourceStatus.DELETED, errorCode = null, updatedAt = at)
    }

    override fun deleteChunks(sourceId: UUID) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE source_id = ?", sourceId)
    }

    override fun enqueue(sourceId: UUID, operation: KnowledgeIndexOperation, dedupeKey: String, at: Instant) {
        jdbc.update(
            """
            INSERT INTO knowledge_index_outbox (
                id, source_id, operation, dedupe_key, status, attempts, next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(), sourceId, operation.name, dedupeKey.take(255), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at),
        )
    }

    @Transactional
    override fun claimBatch(limit: Int, at: Instant): List<KnowledgeIndexJob> {
        val staleBefore = at.minus(CLAIM_TIMEOUT)
        val rows = jdbc.query(
            """
            SELECT id, source_id, operation, attempts
            FROM knowledge_index_outbox
            WHERE (status = 'PENDING' AND next_attempt_at <= ?)
               OR (status = 'PROCESSING' AND claimed_at < ?)
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> KnowledgeIndexJob(rs.getObject("id", UUID::class.java), rs.getObject("source_id", UUID::class.java), KnowledgeIndexOperation.valueOf(rs.getString("operation")), rs.getInt("attempts") + 1) },
            Timestamp.from(at), Timestamp.from(staleBefore), limit,
        )
        rows.forEach { job ->
            jdbc.update(
                "UPDATE knowledge_index_outbox SET status = 'PROCESSING', attempts = ?, claimed_at = ?, updated_at = ? WHERE id = ?",
                job.attempts, Timestamp.from(at), Timestamp.from(at), job.id,
            )
        }
        return rows
    }

    override fun markCompleted(id: UUID, at: Instant) {
        jdbc.update(
            "UPDATE knowledge_index_outbox SET status = 'COMPLETED', completed_at = ?, updated_at = ?, error_code = NULL WHERE id = ?",
            Timestamp.from(at), Timestamp.from(at), id,
        )
    }

    override fun markRetry(id: UUID, errorCode: String, nextAttemptAt: Instant, at: Instant) {
        jdbc.update(
            """
            UPDATE knowledge_index_outbox SET status = 'PENDING', next_attempt_at = ?, claimed_at = NULL,
                error_code = ?, updated_at = ? WHERE id = ?
            """.trimIndent(),
            Timestamp.from(nextAttemptAt), errorCode.take(120), Timestamp.from(at), id,
        )
    }

    override fun markDead(id: UUID, errorCode: String, at: Instant) {
        jdbc.update(
            "UPDATE knowledge_index_outbox SET status = 'DEAD', error_code = ?, updated_at = ? WHERE id = ?",
            errorCode.take(120), Timestamp.from(at), id,
        )
    }

    override fun search(request: SemanticSearchRequest): List<RetrievedKnowledge> = named.query(
        """
        WITH eligible AS (
            SELECT c.id, c.source_id, c.normalized_text, c.search_vector, c.embedding, c.page,
                   s.type, s.resource_id, s.title
            FROM knowledge_chunk c
            JOIN knowledge_source s ON s.id = c.source_id
            WHERE s.household_id = :householdId AND s.pet_id = :petId AND s.status = 'READY'
        ),
        lexical AS (
            SELECT e.*, ts_rank_cd(e.search_vector, websearch_to_tsquery('portuguese', :query)) AS lexical_score,
                   ROW_NUMBER() OVER (ORDER BY ts_rank_cd(e.search_vector, websearch_to_tsquery('portuguese', :query)) DESC, e.id) AS lexical_rank
            FROM eligible e
            WHERE e.search_vector @@ websearch_to_tsquery('portuguese', :query)
            ORDER BY lexical_score DESC, e.id
            LIMIT 20
        ),
        semantic AS (
            SELECT e.*, (1 - (e.embedding <=> CAST(:embedding AS public.vector))) AS semantic_score,
                   ROW_NUMBER() OVER (ORDER BY e.embedding <=> CAST(:embedding AS public.vector), e.id) AS semantic_rank
            FROM eligible e
            ORDER BY e.embedding <=> CAST(:embedding AS public.vector), e.id
            LIMIT 20
        ),
        candidate_ids AS (
            SELECT id FROM lexical UNION SELECT id FROM semantic
        ),
        fused AS (
            SELECT ids.id,
                   COALESCE(1.0 / (60 + l.lexical_rank), 0) + COALESCE(1.0 / (60 + s.semantic_rank), 0) AS combined_score,
                   COALESCE(l.lexical_score, 0) AS lexical_score,
                   COALESCE(s.semantic_score, 0) AS semantic_score
            FROM candidate_ids ids
            LEFT JOIN lexical l ON l.id = ids.id
            LEFT JOIN semantic s ON s.id = ids.id
        )
        SELECT e.id, e.source_id, e.type, e.resource_id, e.title, e.normalized_text, e.page,
               f.lexical_score, f.semantic_score, f.combined_score
        FROM fused f JOIN eligible e ON e.id = f.id
        WHERE f.lexical_score > 0 OR f.semantic_score >= :minimumSemanticScore
        ORDER BY f.combined_score DESC, f.semantic_score DESC, e.id
        LIMIT :limit
        """.trimIndent(),
        mapOf(
            "householdId" to request.householdId.value,
            "petId" to request.petId.value,
            "query" to request.query,
            "embedding" to vectorLiteral(request.embedding),
            "limit" to request.limit,
            "minimumSemanticScore" to request.minimumSemanticScore,
        ),
    ) { rs, _ ->
        RetrievedKnowledge(
            rs.getObject("id", UUID::class.java), rs.getObject("source_id", UUID::class.java),
            KnowledgeSourceType.valueOf(rs.getString("type")), rs.getObject("resource_id", UUID::class.java),
            rs.getString("title"), rs.getString("normalized_text"), rs.getObject("page") as Int?,
            rs.getDouble("lexical_score"), rs.getDouble("semantic_score"), rs.getDouble("combined_score"),
        )
    }

    override fun save(answer: AssistantAnswerAudit): AssistantAnswerAudit {
        jdbc.update(
            """
            INSERT INTO assistant_answer (
                id, household_id, actor_tutor_id, pet_id, kind, question_hash, insufficient_evidence,
                citation_count, provider, model, prompt_version, corpus_version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            answer.id, answer.householdId.value, answer.actorTutorId.value, answer.petId.value, answer.kind.name,
            answer.questionHash, answer.insufficientEvidence, answer.citationCount, answer.provider, answer.model,
            answer.promptVersion, answer.corpusVersion, Timestamp.from(answer.createdAt),
        )
        return answer
    }

    override fun findAnswerByIdAndHousehold(id: UUID, householdId: HouseholdId): AssistantAnswerAudit? = jdbc.query(
        "SELECT * FROM assistant_answer WHERE id = ? AND household_id = ?",
        { rs, _ -> answer(rs) },
        id, householdId.value,
    ).firstOrNull()

    override fun saveFeedback(feedback: AssistantAnswerFeedback): AssistantAnswerFeedback {
        jdbc.update(
            """
            INSERT INTO assistant_feedback (
                id, draft_id, answer_id, household_id, actor_tutor_id, positive, corrected_fields, reason, comment, created_at
            ) VALUES (?, NULL, ?, ?, ?, ?, '[]'::jsonb, ?, ?, ?)
            """.trimIndent(),
            feedback.id, feedback.answerId, feedback.householdId.value, feedback.actorTutorId.value,
            feedback.positive, feedback.reason, feedback.comment, Timestamp.from(feedback.createdAt),
        )
        return feedback
    }

    private fun KnowledgeSource.params() = MapSqlParameterSource()
        .addValue("id", id)
        .addValue("householdId", householdId.value)
        .addValue("petId", petId.value)
        .addValue("type", type.name)
        .addValue("resourceId", resourceId)
        .addValue("resourceVersion", resourceVersion)
        .addValue("title", title.take(180))
        .addValue("checksum", checksum)
        .addValue("language", language.take(16))
        .addValue("extractorVersion", extractorVersion.take(80))
        .addValue("chunkerVersion", chunkerVersion.take(80))
        .addValue("embeddingModel", embeddingModel.take(120))
        .addValue("createdAt", Timestamp.from(createdAt))
        .addValue("updatedAt", Timestamp.from(updatedAt))

    private fun answer(rs: ResultSet) = AssistantAnswerAudit(
        rs.getObject("id", UUID::class.java), HouseholdId(rs.getObject("household_id", UUID::class.java)),
        TutorId(rs.getLong("actor_tutor_id")), PetId(rs.getLong("pet_id")), AssistantAnswerKind.valueOf(rs.getString("kind")),
        rs.getString("question_hash"), rs.getBoolean("insufficient_evidence"), rs.getInt("citation_count"),
        rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"), rs.getString("corpus_version"),
        rs.getTimestamp("created_at").toInstant(),
    )

    private fun vectorLiteral(vector: FloatArray): String = vector.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toString() }

    companion object {
        private val CLAIM_TIMEOUT = Duration.ofMinutes(10)
        private val SOURCE_MAPPER = RowMapper<KnowledgeSource> { rs, _ ->
            KnowledgeSource(
                rs.getObject("id", UUID::class.java), HouseholdId(rs.getObject("household_id", UUID::class.java)),
                PetId(rs.getLong("pet_id")), KnowledgeSourceType.valueOf(rs.getString("type")),
                rs.getObject("resource_id", UUID::class.java), rs.getString("resource_version"), rs.getString("title"),
                rs.getString("checksum"), rs.getString("language"), KnowledgeSourceStatus.valueOf(rs.getString("status")),
                rs.getString("extractor_version"), rs.getString("chunker_version"), rs.getString("embedding_model"),
                rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
