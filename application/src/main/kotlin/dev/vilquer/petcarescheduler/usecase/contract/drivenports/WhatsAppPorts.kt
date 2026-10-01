package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversation
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentity
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentityId
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ProtectedValue(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyVersion: Int,
)

data class WhatsAppLinkToken(
    val id: UUID = UUID.randomUUID(),
    val tutorId: TutorId,
    val householdId: HouseholdId,
    val businessPhoneNumberId: String,
    val tokenHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val consumedAt: Instant? = null,
)

enum class WhatsAppInboundType { TEXT, INTERACTIVE, STATUS }
enum class WhatsAppWorkStatus { PENDING, PROCESSING, DONE, FAILED, DEAD }
enum class WhatsAppDeliveryStatus { PENDING, SENDING, RETRY, SENT, DELIVERED, READ, FAILED, DEAD, CANCELLED }
enum class WhatsAppOutboundType { TEXT, INTERACTIVE }

data class WhatsAppInboundRecord(
    val id: UUID = UUID.randomUUID(),
    val providerEventKey: String,
    val providerMessageId: String,
    val businessPhoneNumberId: String,
    val senderLookupHmac: String?,
    val sender: ProtectedValue?,
    val content: ProtectedValue?,
    val type: WhatsAppInboundType,
    val providerStatus: String? = null,
    val eventAt: Instant,
    val receivedAt: Instant,
    val status: WhatsAppWorkStatus = WhatsAppWorkStatus.PENDING,
    val attempts: Int = 0,
)

data class WhatsAppButton(val id: String, val title: String)

data class WhatsAppOutboundContent(
    val type: WhatsAppOutboundType,
    val body: String,
    val buttons: List<WhatsAppButton> = emptyList(),
)

data class WhatsAppOutboxRecord(
    val id: UUID = UUID.randomUUID(),
    val identityId: WhatsAppIdentityId,
    val householdId: HouseholdId,
    val dedupeKey: String,
    val content: ProtectedValue,
    val type: WhatsAppOutboundType,
    val status: WhatsAppDeliveryStatus = WhatsAppDeliveryStatus.PENDING,
    val providerMessageId: String? = null,
    val eventAt: Instant? = null,
    val attempts: Int = 0,
    val createdAt: Instant,
)

interface WhatsAppLinkTokenRepositoryPort {
    fun save(token: WhatsAppLinkToken): WhatsAppLinkToken
    fun revokeActive(tutorId: TutorId, businessPhoneNumberId: String, at: Instant)
    fun consume(tokenHash: String, businessPhoneNumberId: String, at: Instant): WhatsAppLinkToken?
}

interface WhatsAppIdentityRepositoryPort {
    fun save(identity: WhatsAppIdentity): WhatsAppIdentity
    fun refreshSecurity(identity: WhatsAppIdentity): WhatsAppIdentity
    fun findActiveByTutor(tutorId: TutorId, businessPhoneNumberId: String): WhatsAppIdentity?
    fun findActiveByLookups(businessPhoneNumberId: String, lookupHmacs: Set<String>): WhatsAppIdentity?
    fun findById(id: WhatsAppIdentityId): WhatsAppIdentity?
    fun revoke(id: WhatsAppIdentityId, at: Instant)
}

interface WhatsAppConversationRepositoryPort {
    fun save(conversation: WhatsAppConversation): WhatsAppConversation
    fun findLatestByIdentity(identityId: WhatsAppIdentityId): WhatsAppConversation?
    fun findByIdentityAndHousehold(identityId: WhatsAppIdentityId, householdId: HouseholdId): WhatsAppConversation?
    fun findByIdentityAndHouseholdForUpdate(identityId: WhatsAppIdentityId, householdId: HouseholdId): WhatsAppConversation?
}

interface WhatsAppInboxRepositoryPort {
    fun insert(record: WhatsAppInboundRecord): Boolean
    fun claimInboxBatch(now: Instant, limit: Int, lease: Duration): List<WhatsAppInboundRecord>
    fun markInboxDone(id: UUID, at: Instant)
    fun markInboxFailed(id: UUID, at: Instant, nextAttemptAt: Instant, dead: Boolean, errorCode: String)
}

interface WhatsAppOutboxRepositoryPort {
    fun enqueue(record: WhatsAppOutboxRecord): Boolean
    fun claimOutboxBatch(now: Instant, limit: Int, lease: Duration): List<WhatsAppOutboxRecord>
    fun markOutboxSent(id: UUID, providerMessageId: String, at: Instant)
    fun markOutboxFailed(id: UUID, at: Instant, nextAttemptAt: Instant, dead: Boolean, errorCode: String)
    fun updateDelivery(providerMessageId: String, status: WhatsAppDeliveryStatus, eventAt: Instant): Boolean
    fun cancelPending(identityId: WhatsAppIdentityId, at: Instant)
}

interface WhatsAppCryptoPort {
    fun lookupCandidates(businessPhoneNumberId: String, canonicalWaId: String): Set<String>
    fun tokenHash(rawToken: String): String
    fun protect(plainText: String, aad: String): ProtectedValue
    fun reveal(value: ProtectedValue, aad: String): String
}

interface WhatsAppWebhookSecurityPort {
    fun verifyChallenge(candidateToken: String): Boolean
    fun verifySignature(rawBody: ByteArray, signatureHeader: String?): Boolean
}

data class WhatsAppGatewayMessage(
    val businessPhoneNumberId: String,
    val recipientWaId: String,
    val content: WhatsAppOutboundContent,
)

fun interface WhatsAppGatewayPort {
    fun send(message: WhatsAppGatewayMessage): String
}
