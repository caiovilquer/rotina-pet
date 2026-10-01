package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import com.fasterxml.jackson.databind.JsonNode
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteractionOutcome
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftActionType
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftInputType
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_care_draft")
class CareDraftJpa {
    @Id lateinit var id: UUID
    @Version var version: Long? = null
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "actor_tutor_id", nullable = false) var actorTutorId: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var channel: CareDraftChannel
    @Column(name = "external_message_id", length = 255) var externalMessageId: String? = null
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var status: CareDraftStatus
    @Enumerated(EnumType.STRING) @Column(name = "input_type", nullable = false) lateinit var inputType: CareDraftInputType
    @Column(name = "input_hash", nullable = false, length = 64) lateinit var inputHash: String
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "structured_payload", nullable = false, columnDefinition = "jsonb") lateinit var structuredPayload: JsonNode
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") lateinit var evidence: JsonNode
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "missing_fields", nullable = false, columnDefinition = "jsonb") lateinit var missingFields: JsonNode
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") lateinit var warnings: JsonNode
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "field_provenance", nullable = false, columnDefinition = "jsonb") lateinit var fieldProvenance: JsonNode
    @Column(length = 80) var provider: String? = null
    @Column(length = 120) var model: String? = null
    @Column(name = "prompt_version", nullable = false, length = 80) lateinit var promptVersion: String
    @Column(name = "plan_id") var planId: UUID? = null
    @Column(name = "failure_code", length = 120) var failureCode: String? = null
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
    @Column(name = "updated_at", nullable = false) lateinit var updatedAt: Instant
    @Column(name = "expires_at", nullable = false) lateinit var expiresAt: Instant
    @Column(name = "confirmed_at") var confirmedAt: Instant? = null
}

@Entity
@Table(name = "ai_care_draft_action")
class CareDraftActionJpa {
    @Id lateinit var id: UUID
    @Column(name = "draft_id", nullable = false) lateinit var draftId: UUID
    @Column(name = "request_id", unique = true) var requestId: UUID? = null
    @Column(name = "actor_tutor_id", nullable = false) var actorTutorId: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var channel: CareDraftChannel
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var action: CareDraftActionType
    @Enumerated(EnumType.STRING) @Column(name = "previous_status") var previousStatus: CareDraftStatus? = null
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false) lateinit var newStatus: CareDraftStatus
    @Column(name = "previous_version") var previousVersion: Long? = null
    @Column(name = "new_version") var newVersion: Long? = null
    @Column(name = "happened_at", nullable = false) lateinit var happenedAt: Instant
}

@Entity
@Table(name = "ai_interaction")
class AiInteractionJpa {
    @Id lateinit var id: UUID
    @Column(name = "draft_id") var draftId: UUID? = null
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "actor_tutor_id", nullable = false) var actorTutorId: Long = 0
    @Column(nullable = false, length = 80) lateinit var operation: String
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var channel: CareDraftChannel
    @Column(nullable = false, length = 80) lateinit var provider: String
    @Column(nullable = false, length = 120) lateinit var model: String
    @Column(name = "prompt_version", nullable = false, length = 80) lateinit var promptVersion: String
    @Column(name = "input_tokens") var inputTokens: Int? = null
    @Column(name = "output_tokens") var outputTokens: Int? = null
    @Column(name = "latency_millis", nullable = false) var latencyMillis: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var outcome: AiInteractionOutcome
    @Column(name = "error_code", length = 120) var errorCode: String? = null
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
}

@Entity
@Table(name = "assistant_feedback")
class AssistantFeedbackJpa {
    @Id lateinit var id: UUID
    @Column(name = "draft_id") var draftId: UUID? = null
    @Column(name = "answer_id") var answerId: UUID? = null
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "actor_tutor_id", nullable = false) var actorTutorId: Long = 0
    @Column(nullable = false) var positive: Boolean = false
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "corrected_fields", nullable = false, columnDefinition = "jsonb") lateinit var correctedFields: JsonNode
    @Column(length = 80) var reason: String? = null
    @Column(length = 1000) var comment: String? = null
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
}
