package dev.vilquer.petcarescheduler.core.domain.integration

import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.time.Instant
import java.util.UUID

@JvmInline value class WhatsAppIdentityId(val value: UUID)
@JvmInline value class WhatsAppConversationId(val value: UUID)

enum class WhatsAppConversationState {
    IDLE,
    AWAITING_LINK,
    AWAITING_HOUSEHOLD,
    AWAITING_PET,
    AWAITING_CLARIFICATION,
    AWAITING_DRAFT_CONFIRMATION,
    AWAITING_COMPLETION_CONFIRMATION,
}

data class WhatsAppIdentity(
    val id: WhatsAppIdentityId = WhatsAppIdentityId(UUID.randomUUID()),
    val tutorId: TutorId,
    val businessPhoneNumberId: String,
    val lookupHmac: String,
    val waIdCiphertext: ByteArray,
    val waIdNonce: ByteArray,
    val encryptionKeyVersion: Int,
    val linkedAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant? = null,
) {
    init {
        require(businessPhoneNumberId.matches(Regex("^[0-9]{5,30}$"))) { "whatsapp_business_phone_id_invalid" }
        require(lookupHmac.matches(Regex("^[a-f0-9]{64}$"))) { "whatsapp_lookup_invalid" }
        require(waIdCiphertext.isNotEmpty() && waIdNonce.size == 12) { "whatsapp_identity_cipher_invalid" }
        require(encryptionKeyVersion > 0) { "whatsapp_key_version_invalid" }
    }
}

data class WhatsAppConversation(
    val id: WhatsAppConversationId = WhatsAppConversationId(UUID.randomUUID()),
    val version: Long? = null,
    val identityId: WhatsAppIdentityId,
    val householdId: HouseholdId,
    val state: WhatsAppConversationState = WhatsAppConversationState.IDLE,
    val pendingDraftId: CareDraftId? = null,
    val pendingDraftVersion: Long? = null,
    val expiresAt: Instant? = null,
    val updatedAt: Instant,
) {
    init {
        require((pendingDraftId == null) == (pendingDraftVersion == null)) { "whatsapp_pending_draft_invalid" }
        require(state != WhatsAppConversationState.AWAITING_DRAFT_CONFIRMATION || pendingDraftId != null) {
            "whatsapp_confirmation_draft_required"
        }
    }

    fun awaitDraft(draftId: CareDraftId, draftVersion: Long, expiresAt: Instant, at: Instant) = copy(
        state = WhatsAppConversationState.AWAITING_DRAFT_CONFIRMATION,
        pendingDraftId = draftId,
        pendingDraftVersion = draftVersion,
        expiresAt = expiresAt,
        updatedAt = at,
    )

    fun awaitCorrection(at: Instant) = copy(
        state = WhatsAppConversationState.AWAITING_CLARIFICATION,
        updatedAt = at,
    )

    fun idle(at: Instant) = copy(
        state = WhatsAppConversationState.IDLE,
        pendingDraftId = null,
        pendingDraftVersion = null,
        expiresAt = null,
        updatedAt = at,
    )
}
