package dev.vilquer.petcarescheduler.usecase.result

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerKind
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import java.time.Instant
import java.util.UUID

data class AssistantCitationResult(
    val sourceType: KnowledgeSourceType,
    val sourceId: UUID,
    val resourceId: UUID,
    val title: String,
    val page: Int?,
    val excerpt: String,
    val contentUrl: String?,
)

data class PetHistoryAnswerResult(
    val answerId: UUID,
    val kind: AssistantAnswerKind,
    val answer: String,
    val citations: List<AssistantCitationResult>,
    val insufficientEvidence: Boolean,
    val suggestedFollowUps: List<String>,
    val generatedAt: Instant,
)

data class KnowledgeSourceResult(
    val id: UUID,
    val type: KnowledgeSourceType,
    val resourceId: UUID,
    val title: String,
    val status: KnowledgeSourceStatus,
    val errorCode: String?,
    val updatedAt: Instant,
)
