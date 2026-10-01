package dev.vilquer.petcarescheduler.usecase.command

import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import java.util.UUID

data class AskPetHistoryQuestionCommand(
    val petId: PetId,
    val question: String,
)

data class AddAssistantAnswerFeedbackCommand(
    val answerId: UUID,
    val positive: Boolean,
    val reason: String? = null,
    val comment: String? = null,
)

data class ReindexKnowledgeSourceCommand(val sourceId: UUID)
