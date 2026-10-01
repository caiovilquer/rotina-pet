package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversation
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversationId
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversationState
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentity
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentityId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ProtectedValue
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppConversationRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppDeliveryStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppIdentityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundRecord
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppLinkToken
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppLinkTokenRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboxRecord
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppWorkStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class WhatsAppPersistenceAdapter(private val jdbc: JdbcTemplate) :
    WhatsAppLinkTokenRepositoryPort,
    WhatsAppIdentityRepositoryPort,
    WhatsAppConversationRepositoryPort,
    WhatsAppInboxRepositoryPort,
    WhatsAppOutboxRepositoryPort {

    override fun save(token: WhatsAppLinkToken): WhatsAppLinkToken {
        jdbc.update(
            """
            insert into whatsapp_link_token
                (id, tutor_id, household_id, business_phone_number_id, token_hash, expires_at, consumed_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            token.id, token.tutorId.value, token.householdId.value, token.businessPhoneNumberId, token.tokenHash,
            timestamp(token.expiresAt), timestamp(token.consumedAt), timestamp(token.createdAt),
        )
        return token
    }

    override fun revokeActive(tutorId: TutorId, businessPhoneNumberId: String, at: Instant) {
        jdbc.update(
            """
            update whatsapp_link_token set revoked_at = ?
             where tutor_id = ? and business_phone_number_id = ?
               and consumed_at is null and revoked_at is null
            """.trimIndent(),
            timestamp(at), tutorId.value, businessPhoneNumberId,
        )
    }

    override fun consume(tokenHash: String, businessPhoneNumberId: String, at: Instant): WhatsAppLinkToken? =
        jdbc.query(
            """
            update whatsapp_link_token
               set consumed_at = ?
             where token_hash = ? and business_phone_number_id = ?
               and consumed_at is null and revoked_at is null and expires_at > ?
            returning *
            """.trimIndent(),
            LINK_TOKEN_MAPPER,
            timestamp(at), tokenHash, businessPhoneNumberId, timestamp(at),
        ).firstOrNull()

    override fun save(identity: WhatsAppIdentity): WhatsAppIdentity {
        jdbc.update(
            """
            insert into whatsapp_identity
                (id, tutor_id, business_phone_number_id, lookup_hmac, wa_id_ciphertext, wa_id_nonce,
                 encryption_key_version, linked_at, last_seen_at, revoked_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            identity.id.value, identity.tutorId.value, identity.businessPhoneNumberId, identity.lookupHmac,
            identity.waIdCiphertext, identity.waIdNonce, identity.encryptionKeyVersion, timestamp(identity.linkedAt),
            timestamp(identity.lastSeenAt), timestamp(identity.revokedAt),
        )
        return identity
    }

    override fun refreshSecurity(identity: WhatsAppIdentity): WhatsAppIdentity {
        val updated = jdbc.update(
            """
            update whatsapp_identity set lookup_hmac = ?, wa_id_ciphertext = ?, wa_id_nonce = ?,
                encryption_key_version = ?, last_seen_at = ?
             where id = ? and revoked_at is null
            """.trimIndent(),
            identity.lookupHmac, identity.waIdCiphertext, identity.waIdNonce, identity.encryptionKeyVersion,
            timestamp(identity.lastSeenAt), identity.id.value,
        )
        check(updated == 1) { "whatsapp_identity_not_active" }
        return identity
    }

    override fun findActiveByTutor(tutorId: TutorId, businessPhoneNumberId: String): WhatsAppIdentity? =
        jdbc.query(
            "select * from whatsapp_identity where tutor_id = ? and business_phone_number_id = ? and revoked_at is null",
            IDENTITY_MAPPER,
            tutorId.value,
            businessPhoneNumberId,
        ).firstOrNull()

    override fun findActiveByLookups(businessPhoneNumberId: String, lookupHmacs: Set<String>): WhatsAppIdentity? {
        if (lookupHmacs.isEmpty()) return null
        val placeholders = lookupHmacs.joinToString(",") { "?" }
        return jdbc.query(
            "select * from whatsapp_identity where business_phone_number_id = ? and lookup_hmac in ($placeholders) and revoked_at is null",
            IDENTITY_MAPPER,
            businessPhoneNumberId,
            *lookupHmacs.toTypedArray(),
        ).firstOrNull()
    }

    override fun findById(id: WhatsAppIdentityId): WhatsAppIdentity? =
        jdbc.query("select * from whatsapp_identity where id = ?", IDENTITY_MAPPER, id.value).firstOrNull()

    override fun revoke(id: WhatsAppIdentityId, at: Instant) {
        jdbc.update("update whatsapp_identity set revoked_at = ?, last_seen_at = ? where id = ? and revoked_at is null", timestamp(at), timestamp(at), id.value)
    }

    override fun save(conversation: WhatsAppConversation): WhatsAppConversation = if (conversation.version == null) {
        jdbc.query(
            """
            insert into whatsapp_conversation
                (id, version, identity_id, household_id, state, pending_draft_id, pending_draft_version, expires_at, updated_at)
            values (?, 0, ?, ?, ?, ?, ?, ?, ?)
            on conflict (identity_id, household_id) do update set
                state = excluded.state,
                pending_draft_id = excluded.pending_draft_id,
                pending_draft_version = excluded.pending_draft_version,
                expires_at = excluded.expires_at,
                updated_at = excluded.updated_at,
                version = whatsapp_conversation.version + 1
            returning *
            """.trimIndent(),
            CONVERSATION_MAPPER,
            conversation.id.value, conversation.identityId.value, conversation.householdId.value, conversation.state.name,
            conversation.pendingDraftId?.value, conversation.pendingDraftVersion, timestamp(conversation.expiresAt), timestamp(conversation.updatedAt),
        ).single()
    } else {
        jdbc.query(
            """
            update whatsapp_conversation set
                state = ?, pending_draft_id = ?, pending_draft_version = ?, expires_at = ?, updated_at = ?, version = version + 1
             where id = ? and version = ?
            returning *
            """.trimIndent(),
            CONVERSATION_MAPPER,
            conversation.state.name, conversation.pendingDraftId?.value, conversation.pendingDraftVersion,
            timestamp(conversation.expiresAt), timestamp(conversation.updatedAt), conversation.id.value, conversation.version,
        ).singleOrNull() ?: throw IllegalStateException("whatsapp_conversation_concurrent_update")
    }

    override fun findLatestByIdentity(identityId: WhatsAppIdentityId): WhatsAppConversation? =
        jdbc.query(
            "select * from whatsapp_conversation where identity_id = ? order by updated_at desc, id desc limit 1",
            CONVERSATION_MAPPER,
            identityId.value,
        ).firstOrNull()

    override fun findByIdentityAndHousehold(identityId: WhatsAppIdentityId, householdId: HouseholdId): WhatsAppConversation? =
        jdbc.query(
            "select * from whatsapp_conversation where identity_id = ? and household_id = ?",
            CONVERSATION_MAPPER,
            identityId.value,
            householdId.value,
        ).firstOrNull()

    override fun findByIdentityAndHouseholdForUpdate(identityId: WhatsAppIdentityId, householdId: HouseholdId): WhatsAppConversation? =
        jdbc.query(
            "select * from whatsapp_conversation where identity_id = ? and household_id = ? for update",
            CONVERSATION_MAPPER,
            identityId.value,
            householdId.value,
        ).firstOrNull()

    override fun insert(record: WhatsAppInboundRecord): Boolean =
        jdbc.update(
            """
            insert into whatsapp_inbox
                (id, provider_event_key, provider_message_id, business_phone_number_id, sender_lookup_hmac,
                 sender_ciphertext, sender_nonce, sender_key_version, content_ciphertext, content_nonce, content_key_version,
                 event_type, provider_status, event_at, received_at, work_status, attempts, next_attempt_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (provider_event_key) do nothing
            """.trimIndent(),
            record.id, record.providerEventKey, record.providerMessageId, record.businessPhoneNumberId, record.senderLookupHmac,
            record.sender?.ciphertext, record.sender?.nonce, record.sender?.keyVersion,
            record.content?.ciphertext, record.content?.nonce, record.content?.keyVersion,
            record.type.name, record.providerStatus, timestamp(record.eventAt), timestamp(record.receivedAt),
            record.status.name, record.attempts, timestamp(record.receivedAt),
        ) == 1

    override fun claimInboxBatch(now: Instant, limit: Int, lease: Duration): List<WhatsAppInboundRecord> =
        jdbc.query(
            """
            with picked as (
                select id from whatsapp_inbox
                 where ((work_status in ('PENDING', 'FAILED') and next_attempt_at <= ?)
                    or (work_status = 'PROCESSING' and claimed_until < ?))
                 order by received_at
                 for update skip locked
                 limit ?
            )
            update whatsapp_inbox i
               set work_status = 'PROCESSING', claimed_until = ?, attempts = attempts + 1
              from picked where i.id = picked.id
            returning i.*
            """.trimIndent(),
            INBOX_MAPPER,
            timestamp(now), timestamp(now), limit, timestamp(now.plus(lease)),
        )

    override fun markInboxDone(id: UUID, at: Instant) {
        jdbc.update(
            "update whatsapp_inbox set work_status = 'DONE', processed_at = ?, claimed_until = null, last_error_code = null where id = ?",
            timestamp(at), id,
        )
    }

    override fun markInboxFailed(id: UUID, at: Instant, nextAttemptAt: Instant, dead: Boolean, errorCode: String) {
        jdbc.update(
            """
            update whatsapp_inbox set work_status = ?, next_attempt_at = ?, claimed_until = null,
                last_error_code = ?, processed_at = case when ? then ? else processed_at end
             where id = ?
            """.trimIndent(),
            if (dead) "DEAD" else "FAILED", timestamp(nextAttemptAt), errorCode, dead, timestamp(at), id,
        )
    }

    override fun enqueue(record: WhatsAppOutboxRecord): Boolean =
        jdbc.update(
            """
            insert into whatsapp_outbox
                (id, identity_id, household_id, dedupe_key, message_type, content_ciphertext, content_nonce,
                 content_key_version, delivery_status, attempts, next_attempt_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (dedupe_key) do nothing
            """.trimIndent(),
            record.id, record.identityId.value, record.householdId.value, record.dedupeKey, record.type.name,
            record.content.ciphertext, record.content.nonce, record.content.keyVersion, record.status.name,
            record.attempts, timestamp(record.createdAt), timestamp(record.createdAt), timestamp(record.createdAt),
        ) == 1

    override fun claimOutboxBatch(now: Instant, limit: Int, lease: Duration): List<WhatsAppOutboxRecord> =
        jdbc.query(
            """
            with picked as (
                select id from whatsapp_outbox
                 where ((delivery_status in ('PENDING', 'RETRY') and next_attempt_at <= ?)
                    or (delivery_status = 'SENDING' and claimed_until < ?))
                 order by created_at
                 for update skip locked
                 limit ?
            )
            update whatsapp_outbox o
               set delivery_status = 'SENDING', claimed_until = ?, attempts = attempts + 1, updated_at = ?
              from picked where o.id = picked.id
            returning o.*
            """.trimIndent(),
            OUTBOX_MAPPER,
            timestamp(now), timestamp(now), limit, timestamp(now.plus(lease)), timestamp(now),
        )

    override fun markOutboxSent(id: UUID, providerMessageId: String, at: Instant) {
        jdbc.update(
            """
            update whatsapp_outbox set delivery_status = 'SENT', provider_message_id = ?, status_event_at = ?,
                sent_at = ?, updated_at = ?, claimed_until = null, last_error_code = null where id = ?
            """.trimIndent(),
            providerMessageId, timestamp(at), timestamp(at), timestamp(at), id,
        )
    }

    override fun markOutboxFailed(id: UUID, at: Instant, nextAttemptAt: Instant, dead: Boolean, errorCode: String) {
        jdbc.update(
            """
            update whatsapp_outbox set delivery_status = ?, next_attempt_at = ?, claimed_until = null,
                last_error_code = ?, updated_at = ? where id = ?
            """.trimIndent(),
            if (dead) "DEAD" else "RETRY", timestamp(nextAttemptAt), errorCode, timestamp(at), id,
        )
    }

    override fun updateDelivery(providerMessageId: String, status: WhatsAppDeliveryStatus, eventAt: Instant): Boolean {
        val updated = jdbc.update(
            """
            update whatsapp_outbox set delivery_status = ?, status_event_at = ?, updated_at = ?
             where provider_message_id = ?
               and (status_event_at is null or status_event_at <= ?)
               and case delivery_status
                   when 'PENDING' then 0 when 'SENDING' then 0 when 'RETRY' then 0 when 'SENT' then 1
                   when 'DELIVERED' then 2 when 'READ' then 3 when 'FAILED' then 0 else 99 end
                   <= ?
            """.trimIndent(),
            status.name, timestamp(eventAt), timestamp(eventAt), providerMessageId, timestamp(eventAt), deliveryRank(status),
        )
        return updated > 0 || jdbc.queryForObject(
            "select exists(select 1 from whatsapp_outbox where provider_message_id = ?)",
            Boolean::class.java,
            providerMessageId,
        ) == true
    }

    override fun cancelPending(identityId: WhatsAppIdentityId, at: Instant) {
        jdbc.update(
            """
            update whatsapp_outbox set delivery_status = 'CANCELLED', updated_at = ?, claimed_until = null
             where identity_id = ? and delivery_status in ('PENDING', 'RETRY')
            """.trimIndent(),
            timestamp(at), identityId.value,
        )
    }

    private fun deliveryRank(status: WhatsAppDeliveryStatus): Int = when (status) {
        WhatsAppDeliveryStatus.SENT -> 1
        WhatsAppDeliveryStatus.DELIVERED -> 2
        WhatsAppDeliveryStatus.READ -> 3
        WhatsAppDeliveryStatus.FAILED -> 1
        else -> 99
    }

    companion object {
        private fun timestamp(value: Instant?): Timestamp? = value?.let(Timestamp::from)
        private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
        private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
        private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

        private val LINK_TOKEN_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            WhatsAppLinkToken(
                id = rs.uuid("id"),
                tutorId = TutorId(rs.getLong("tutor_id")),
                householdId = HouseholdId(rs.uuid("household_id")),
                businessPhoneNumberId = rs.getString("business_phone_number_id"),
                tokenHash = rs.getString("token_hash"),
                expiresAt = rs.instant("expires_at"),
                createdAt = rs.instant("created_at"),
                consumedAt = rs.instantOrNull("consumed_at"),
            )
        }
        private val IDENTITY_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            WhatsAppIdentity(
                id = WhatsAppIdentityId(rs.uuid("id")),
                tutorId = TutorId(rs.getLong("tutor_id")),
                businessPhoneNumberId = rs.getString("business_phone_number_id"),
                lookupHmac = rs.getString("lookup_hmac"),
                waIdCiphertext = rs.getBytes("wa_id_ciphertext"),
                waIdNonce = rs.getBytes("wa_id_nonce"),
                encryptionKeyVersion = rs.getInt("encryption_key_version"),
                linkedAt = rs.instant("linked_at"),
                lastSeenAt = rs.instant("last_seen_at"),
                revokedAt = rs.instantOrNull("revoked_at"),
            )
        }
        private val CONVERSATION_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            WhatsAppConversation(
                id = WhatsAppConversationId(rs.uuid("id")),
                version = rs.getLong("version"),
                identityId = WhatsAppIdentityId(rs.uuid("identity_id")),
                householdId = HouseholdId(rs.uuid("household_id")),
                state = WhatsAppConversationState.valueOf(rs.getString("state")),
                pendingDraftId = rs.getObject("pending_draft_id", UUID::class.java)?.let(::CareDraftId),
                pendingDraftVersion = rs.getObject("pending_draft_version")?.let { rs.getLong("pending_draft_version") },
                expiresAt = rs.instantOrNull("expires_at"),
                updatedAt = rs.instant("updated_at"),
            )
        }
        private val INBOX_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            WhatsAppInboundRecord(
                id = rs.uuid("id"),
                providerEventKey = rs.getString("provider_event_key"),
                providerMessageId = rs.getString("provider_message_id"),
                businessPhoneNumberId = rs.getString("business_phone_number_id"),
                senderLookupHmac = rs.getString("sender_lookup_hmac"),
                sender = protected(rs, "sender"),
                content = protected(rs, "content"),
                type = WhatsAppInboundType.valueOf(rs.getString("event_type")),
                providerStatus = rs.getString("provider_status"),
                eventAt = rs.instant("event_at"),
                receivedAt = rs.instant("received_at"),
                status = WhatsAppWorkStatus.valueOf(rs.getString("work_status")),
                attempts = rs.getInt("attempts"),
            )
        }
        private val OUTBOX_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            WhatsAppOutboxRecord(
                id = rs.uuid("id"),
                identityId = WhatsAppIdentityId(rs.uuid("identity_id")),
                householdId = HouseholdId(rs.uuid("household_id")),
                dedupeKey = rs.getString("dedupe_key"),
                content = requireNotNull(protected(rs, "content")),
                type = WhatsAppOutboundType.valueOf(rs.getString("message_type")),
                status = WhatsAppDeliveryStatus.valueOf(rs.getString("delivery_status")),
                providerMessageId = rs.getString("provider_message_id"),
                eventAt = rs.instantOrNull("status_event_at"),
                attempts = rs.getInt("attempts"),
                createdAt = rs.instant("created_at"),
            )
        }

        private fun protected(rs: ResultSet, prefix: String): ProtectedValue? {
            val ciphertext = rs.getBytes("${prefix}_ciphertext") ?: return null
            return ProtectedValue(ciphertext, rs.getBytes("${prefix}_nonce"), rs.getInt("${prefix}_key_version"))
        }
    }
}
