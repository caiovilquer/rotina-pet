package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.UpstreamServiceException
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftStatus
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversation
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppConversationState
import dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentity
import dev.vilquer.petcarescheduler.usecase.command.CancelCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.ConfirmCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.GenerateCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.IngestWhatsAppEventCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ProtectedValue
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppButton
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppConversationRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppCryptoPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppDeliveryStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppIdentityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundRecord
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppLinkToken
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppLinkTokenRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboxRecord
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundContent
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareDraftUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppConnectionUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppInboundUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppOutboxUseCase
import dev.vilquer.petcarescheduler.usecase.result.CareDraftResult
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppConnectionResult
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppConnectionStatus
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppLinkTokenResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

data class WhatsAppSettings(
    val enabled: Boolean = false,
    val businessPhoneNumberId: String = "",
    val businessPhoneNumber: String = "",
    val linkTtl: Duration = Duration.ofMinutes(10),
    val conversationTtl: Duration = Duration.ofHours(24),
    val inboxBatchSize: Int = 20,
    val outboxBatchSize: Int = 20,
    val maxAttempts: Int = 5,
) {
    init {
        require(!linkTtl.isNegative && !linkTtl.isZero) { "whatsapp_link_ttl_invalid" }
        require(!conversationTtl.isNegative && !conversationTtl.isZero) { "whatsapp_conversation_ttl_invalid" }
        require(inboxBatchSize in 1..100 && outboxBatchSize in 1..100) { "whatsapp_batch_size_invalid" }
        require(maxAttempts in 1..20) { "whatsapp_max_attempts_invalid" }
        if (enabled) {
            require(businessPhoneNumberId.matches(Regex("^[0-9]{5,30}$"))) { "whatsapp_business_phone_id_invalid" }
            require(businessPhoneNumber.matches(Regex("^[0-9]{8,20}$"))) { "whatsapp_business_phone_invalid" }
        }
    }
}

class WhatsAppAppService(
    private val linkTokens: WhatsAppLinkTokenRepositoryPort,
    private val identities: WhatsAppIdentityRepositoryPort,
    private val conversations: WhatsAppConversationRepositoryPort,
    private val inbox: WhatsAppInboxRepositoryPort,
    private val outbox: WhatsAppOutboxRepositoryPort,
    private val crypto: WhatsAppCryptoPort,
    private val gateway: WhatsAppGatewayPort,
    private val careDrafts: CareDraftUseCase,
    private val households: HouseholdRepositoryPort,
    private val members: HouseholdMemberRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val settings: WhatsAppSettings,
) : WhatsAppConnectionUseCase, WhatsAppInboundUseCase, WhatsAppOutboxUseCase {

    override fun status(access: HouseholdAccess): WhatsAppConnectionResult {
        if (!settings.enabled) return WhatsAppConnectionResult(WhatsAppConnectionStatus.UNAVAILABLE, access.householdId.value.toString())
        val identity = identities.findActiveByTutor(access.actorTutorId, settings.businessPhoneNumberId)
            ?: return WhatsAppConnectionResult(WhatsAppConnectionStatus.DISCONNECTED, access.householdId.value.toString())
        val activeConversation = conversations.findLatestByIdentity(identity.id)
        if (activeConversation?.householdId != access.householdId) {
            return WhatsAppConnectionResult(WhatsAppConnectionStatus.DISCONNECTED, access.householdId.value.toString())
        }
        val waId = crypto.reveal(identity.protectedWaId(), identityAad(identity))
        return WhatsAppConnectionResult(
            status = WhatsAppConnectionStatus.CONNECTED,
            householdId = access.householdId.value.toString(),
            maskedNumber = mask(waId),
            linkedAt = identity.linkedAt,
        )
    }

    override fun createLinkToken(access: HouseholdAccess): WhatsAppLinkTokenResult {
        requireOwner(access)
        requireEnabled()
        val now = now()
        val code = "rp_${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SECURE_RANDOM::nextBytes))}"
        transaction.execute {
            linkTokens.revokeActive(access.actorTutorId, settings.businessPhoneNumberId, now)
            linkTokens.save(
                WhatsAppLinkToken(
                    tutorId = access.actorTutorId,
                    householdId = access.householdId,
                    businessPhoneNumberId = settings.businessPhoneNumberId,
                    tokenHash = crypto.tokenHash(code),
                    expiresAt = now.plus(settings.linkTtl),
                    createdAt = now,
                ),
            )
        }
        val text = URLEncoder.encode("VINCULAR $code", StandardCharsets.UTF_8)
        return WhatsAppLinkTokenResult(code, "https://wa.me/${settings.businessPhoneNumber}?text=$text", now.plus(settings.linkTtl))
    }

    override fun revoke(access: HouseholdAccess) {
        requireOwner(access)
        if (!settings.enabled) return
        val now = now()
        identities.findActiveByTutor(access.actorTutorId, settings.businessPhoneNumberId)?.let { identity ->
            transaction.execute {
                outbox.cancelPending(identity.id, now)
                identities.revoke(identity.id, now)
            }
        }
    }

    override fun ingest(command: IngestWhatsAppEventCommand): Boolean {
        requireEnabled()
        require(command.providerEventKey.isNotBlank() && command.providerEventKey.length <= 300) { "whatsapp_event_key_invalid" }
        require(command.providerMessageId.isNotBlank() && command.providerMessageId.length <= 255) { "whatsapp_message_id_invalid" }
        require(command.businessPhoneNumberId == settings.businessPhoneNumberId) { "whatsapp_phone_number_mismatch" }
        val receivedAt = now()
        val id = UUID.randomUUID()
        val sender = command.senderWaId?.let(::canonicalWaId)
        val content = command.content?.trim()?.also {
            require(it.isNotEmpty() && it.length <= MAX_INBOUND_CONTENT) { "whatsapp_content_invalid" }
        }
        return inbox.insert(
            WhatsAppInboundRecord(
                id = id,
                providerEventKey = command.providerEventKey,
                providerMessageId = command.providerMessageId,
                businessPhoneNumberId = command.businessPhoneNumberId,
                senderLookupHmac = sender?.let { crypto.lookupCandidates(command.businessPhoneNumberId, it).first() },
                sender = sender?.let { crypto.protect(it, inboxAad(id, "sender")) },
                content = content?.let { crypto.protect(it, inboxAad(id, "content")) },
                type = command.type,
                providerStatus = command.providerStatus,
                eventAt = command.eventAt,
                receivedAt = receivedAt,
            ),
        )
    }

    override fun processBatch(): Int {
        if (!settings.enabled) return 0
        val records = inbox.claimInboxBatch(now(), settings.inboxBatchSize, CLAIM_LEASE)
        records.forEach(::processSafely)
        return records.size
    }

    override fun relayBatch(): Int {
        if (!settings.enabled) return 0
        val records = outbox.claimOutboxBatch(now(), settings.outboxBatchSize, CLAIM_LEASE)
        records.forEach { record ->
            try {
                val identity = identities.findById(record.identityId) ?: error("whatsapp_identity_missing")
                val recipient = crypto.reveal(identity.protectedWaId(), identityAad(identity))
                val content = decodeContent(crypto.reveal(record.content, outboxAad(record.id)))
                val providerId = gateway.send(WhatsAppGatewayMessage(identity.businessPhoneNumberId, recipient, content))
                outbox.markOutboxSent(record.id, providerId, now())
            } catch (exception: Exception) {
                val at = now()
                val dead = record.attempts >= settings.maxAttempts
                outbox.markOutboxFailed(record.id, at, at.plus(backoff(record.attempts)), dead, safeErrorCode(exception))
            }
        }
        return records.size
    }

    private fun processSafely(record: WhatsAppInboundRecord) {
        try {
            when (record.type) {
                WhatsAppInboundType.STATUS -> processStatus(record)
                WhatsAppInboundType.TEXT, WhatsAppInboundType.INTERACTIVE -> processMessage(record)
            }
            inbox.markInboxDone(record.id, now())
        } catch (exception: Exception) {
            val at = now()
            val dead = record.attempts >= settings.maxAttempts
            inbox.markInboxFailed(record.id, at, at.plus(backoff(record.attempts)), dead, safeErrorCode(exception))
        }
    }

    private fun processStatus(record: WhatsAppInboundRecord) {
        val status = when (record.providerStatus?.uppercase()) {
            "SENT" -> WhatsAppDeliveryStatus.SENT
            "DELIVERED" -> WhatsAppDeliveryStatus.DELIVERED
            "READ" -> WhatsAppDeliveryStatus.READ
            "FAILED" -> WhatsAppDeliveryStatus.FAILED
            else -> return
        }
        if (!outbox.updateDelivery(record.providerMessageId, status, record.eventAt)) {
            error("whatsapp_outbox_status_target_missing")
        }
    }

    private fun processMessage(record: WhatsAppInboundRecord) {
        val sender = requireNotNull(record.sender) { "whatsapp_sender_missing" }
            .let { crypto.reveal(it, inboxAad(record.id, "sender")) }
        val lookupCandidates = crypto.lookupCandidates(record.businessPhoneNumberId, sender)
        val identity = identities.findActiveByLookups(record.businessPhoneNumberId, lookupCandidates)?.let { current ->
            val protected = crypto.protect(sender, identityAad(current))
            identities.refreshSecurity(
                current.copy(
                    lookupHmac = lookupCandidates.first(),
                    waIdCiphertext = protected.ciphertext,
                    waIdNonce = protected.nonce,
                    encryptionKeyVersion = protected.keyVersion,
                    lastSeenAt = now(),
                ),
            )
        }
        val content = requireNotNull(record.content) { "whatsapp_content_missing" }
            .let { crypto.reveal(it, inboxAad(record.id, "content")) }
        val rawToken = LINK_PATTERN.matchEntire(content.trim())?.groupValues?.get(1)
        if (rawToken != null) {
            processLink(record, sender, lookupCandidates.first(), rawToken, identity)
            return
        }
        if (identity == null) {
            return
        } else {
            processLinked(record, identity, content)
        }
    }

    private fun processLink(
        record: WhatsAppInboundRecord,
        sender: String,
        lookup: String,
        rawToken: String,
        currentIdentity: WhatsAppIdentity?,
    ) {
        val now = now()
        transaction.execute {
            val token = linkTokens.consume(crypto.tokenHash(rawToken), record.businessPhoneNumberId, now) ?: return@execute
            val member = members.findAccessForUpdate(token.tutorId, token.householdId) ?: return@execute
            if (member.role != HouseholdRole.OWNER) return@execute
            val identity = if (currentIdentity != null) {
                if (currentIdentity.tutorId != token.tutorId) return@execute
                currentIdentity
            } else {
                identities.findActiveByTutor(token.tutorId, record.businessPhoneNumberId)?.let { current ->
                    outbox.cancelPending(current.id, now)
                    identities.revoke(current.id, now)
                }
                val id = dev.vilquer.petcarescheduler.core.domain.integration.WhatsAppIdentityId(UUID.randomUUID())
                val protected = crypto.protect(sender, "identity:${id.value}:${record.businessPhoneNumberId}")
                identities.save(
                    WhatsAppIdentity(
                        id = id,
                        tutorId = token.tutorId,
                        businessPhoneNumberId = record.businessPhoneNumberId,
                        lookupHmac = lookup,
                        waIdCiphertext = protected.ciphertext,
                        waIdNonce = protected.nonce,
                        encryptionKeyVersion = protected.keyVersion,
                        linkedAt = now,
                        lastSeenAt = now,
                    ),
                )
            }
            conversations.save(WhatsAppConversation(identityId = identity.id, householdId = token.householdId, updatedAt = now))
            enqueue(identity, token.householdId, "link:${record.providerEventKey}", WhatsAppOutboundContent(
                WhatsAppOutboundType.TEXT,
                "Tudo certo! Seu WhatsApp foi vinculado. Envie uma instrução, por exemplo: dar antipulgas para o pet amanhã às 9h.",
            ), now)
        }
    }

    private fun processLinked(record: WhatsAppInboundRecord, identity: WhatsAppIdentity, content: String) {
        val householdId = conversationHousehold(identity)
        val access = access(identity, householdId)
        if (access == null) {
            transaction.execute {
                enqueue(identity, householdId, "permission:${record.providerEventKey}", WhatsAppOutboundContent(
                    WhatsAppOutboundType.TEXT,
                    "O vínculo perdeu a permissão de proprietário. Reconecte pelo RotinaPet para continuar.",
                ), now())
                identities.revoke(identity.id, now())
            }
            return
        }
        val conversation = conversations.findByIdentityAndHousehold(identity.id, householdId)
            ?: conversations.save(WhatsAppConversation(identityId = identity.id, householdId = householdId, updatedAt = now()))
        if (record.type == WhatsAppInboundType.INTERACTIVE) {
            processButton(record, identity, conversation, access, content)
        } else {
            processText(record, identity, conversation, access, content)
        }
    }

    private fun processText(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        access: HouseholdAccess,
        content: String,
    ) {
        val normalized = content.trim()
        if (normalized.equals("AJUDA", true)) {
            transaction.execute {
                enqueue(identity, access.householdId, "help:${record.providerEventKey}", WhatsAppOutboundContent(
                    WhatsAppOutboundType.TEXT,
                    "Descreva o cuidado com pet, data e horário. Eu preparo um rascunho; nada é criado antes de você confirmar. Envie CANCELAR para desistir.",
                ), now())
            }
            return
        }
        if (normalized.equals("CANCELAR", true) && conversation.pendingDraftId != null) {
            cancelDraft(record, identity, conversation, access)
            return
        }
        val pendingDraftId = conversation.pendingDraftId
        if (pendingDraftId != null && conversation.state != WhatsAppConversationState.IDLE) {
            runCatching {
                careDrafts.cancel(
                    CancelCareDraftCommand(
                        pendingDraftId,
                        requireNotNull(conversation.pendingDraftVersion),
                        deterministicId("replace:${record.providerEventKey}"),
                    ),
                    access,
                )
            }
        }
        val draft = careDrafts.generate(
            GenerateCareDraftCommand(
                instruction = normalized,
                requestId = deterministicId("draft:${record.providerEventKey}"),
                channel = CareDraftChannel.WHATSAPP,
                externalMessageId = record.providerMessageId,
            ),
            access,
        )
        publishDraft(record, identity, conversation, access.householdId, draft)
    }

    private fun publishDraft(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        householdId: HouseholdId,
        draft: CareDraftResult,
    ) {
        val now = now()
        val version = requireNotNull(draft.version)
        val message = when (draft.status) {
            CareDraftStatus.READY -> WhatsAppOutboundContent(
                WhatsAppOutboundType.INTERACTIVE,
                draftSummary(draft),
                listOf(
                    WhatsAppButton("draft.confirm|${draft.id}|$version", "Confirmar"),
                    WhatsAppButton("draft.correct|${draft.id}|$version", "Corrigir"),
                    WhatsAppButton("draft.cancel|${draft.id}|$version", "Cancelar"),
                ),
            )
            CareDraftStatus.NEEDS_INPUT -> WhatsAppOutboundContent(
                WhatsAppOutboundType.INTERACTIVE,
                "O rascunho precisa de mais detalhes: ${missingLabels(draft.missingFields)}. Toque em Corrigir e envie a descrição completa.",
                listOf(
                    WhatsAppButton("draft.correct|${draft.id}|$version", "Corrigir"),
                    WhatsAppButton("draft.cancel|${draft.id}|$version", "Cancelar"),
                ),
            )
            else -> WhatsAppOutboundContent(
                WhatsAppOutboundType.TEXT,
                "Não consegui montar o rascunho agora. Tente novamente ou use o formulário no RotinaPet.",
            )
        }
        transaction.execute {
            val updated = if (draft.status in setOf(CareDraftStatus.READY, CareDraftStatus.NEEDS_INPUT)) {
                val pending = conversation.awaitDraft(
                    CareDraftId(draft.id),
                    version,
                    minOf(draft.expiresAt, now.plus(settings.conversationTtl)),
                    now,
                )
                if (draft.status == CareDraftStatus.NEEDS_INPUT) pending.awaitCorrection(now) else pending
            } else conversation.idle(now)
            conversations.save(updated)
            enqueue(identity, householdId, "draft:${record.providerEventKey}", message, now)
        }
    }

    private fun processButton(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        access: HouseholdAccess,
        buttonId: String,
    ) {
        val parts = buttonId.split('|')
        val allowedActions = setOf("draft.confirm", "draft.correct", "draft.cancel")
        val draftId = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val version = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size != 3 || parts.firstOrNull() !in allowedActions || draftId == null || version == null || conversation.pendingDraftId?.value != draftId ||
            conversation.pendingDraftVersion != version || conversation.expiresAt?.let { !now().isBefore(it) } == true
        ) {
            transaction.execute {
                enqueue(identity, access.householdId, "stale:${record.providerEventKey}", WhatsAppOutboundContent(
                    WhatsAppOutboundType.TEXT,
                    "Esse botão não corresponde mais ao rascunho atual. Envie uma nova instrução para continuar.",
                ), now())
            }
            return
        }
        when (parts[0]) {
            "draft.confirm" -> confirmDraft(record, identity, conversation, access)
            "draft.correct" -> requestCorrection(record, identity, conversation, access.householdId)
            "draft.cancel" -> cancelDraft(record, identity, conversation, access)
            else -> Unit
        }
    }

    private fun confirmDraft(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        access: HouseholdAccess,
    ) {
        val confirmed = careDrafts.confirm(
            ConfirmCareDraftCommand(
                requireNotNull(conversation.pendingDraftId),
                requireNotNull(conversation.pendingDraftVersion),
                deterministicId("confirm:${record.providerEventKey}"),
            ),
            access,
        )
        transaction.execute {
            conversations.save(conversation.idle(now()))
            enqueue(identity, access.householdId, "confirm:${record.providerEventKey}", WhatsAppOutboundContent(
                WhatsAppOutboundType.TEXT,
                "Plano “${confirmed.plan.title}” criado com sucesso. Você já pode acompanhá-lo no RotinaPet.",
            ), now())
        }
    }

    private fun requestCorrection(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        householdId: HouseholdId,
    ) {
        transaction.execute {
            conversations.save(conversation.awaitCorrection(now()))
            enqueue(identity, householdId, "correct:${record.providerEventKey}", WhatsAppOutboundContent(
                WhatsAppOutboundType.TEXT,
                "Envie agora a descrição completa corrigida. O rascunho anterior será substituído.",
            ), now())
        }
    }

    private fun cancelDraft(
        record: WhatsAppInboundRecord,
        identity: WhatsAppIdentity,
        conversation: WhatsAppConversation,
        access: HouseholdAccess,
    ) {
        careDrafts.cancel(
            CancelCareDraftCommand(
                requireNotNull(conversation.pendingDraftId),
                requireNotNull(conversation.pendingDraftVersion),
                deterministicId("cancel:${record.providerEventKey}"),
            ),
            access,
        )
        transaction.execute {
            conversations.save(conversation.idle(now()))
            enqueue(identity, access.householdId, "cancel:${record.providerEventKey}", WhatsAppOutboundContent(
                WhatsAppOutboundType.TEXT,
                "Rascunho cancelado. Quando quiser, envie uma nova instrução.",
            ), now())
        }
    }

    private fun conversationHousehold(identity: WhatsAppIdentity): HouseholdId {
        return conversations.findLatestByIdentity(identity.id)?.householdId
            ?: throw ForbiddenException("Nenhuma família disponível para este vínculo")
    }

    private fun access(identity: WhatsAppIdentity, householdId: HouseholdId): HouseholdAccess? {
        val member = members.findAccess(identity.tutorId, householdId) ?: return null
        if (member.role != HouseholdRole.OWNER) return null
        val household = households.findById(householdId) ?: return null
        return HouseholdAccess(householdId, identity.tutorId, member.role, household.timezone)
    }

    private fun enqueue(
        identity: WhatsAppIdentity,
        householdId: HouseholdId,
        dedupeKey: String,
        content: WhatsAppOutboundContent,
        at: Instant,
    ) {
        validateOutbound(content)
        val id = UUID.randomUUID()
        outbox.enqueue(
            WhatsAppOutboxRecord(
                id = id,
                identityId = identity.id,
                householdId = householdId,
                dedupeKey = dedupeKey,
                content = crypto.protect(encodeContent(content), outboxAad(id)),
                type = content.type,
                createdAt = at,
            ),
        )
    }

    private fun validateOutbound(content: WhatsAppOutboundContent) {
        require(content.body.isNotBlank() && content.body.length <= 1_024) { "whatsapp_body_invalid" }
        require(content.buttons.size <= 3) { "whatsapp_buttons_invalid" }
        content.buttons.forEach {
            require(it.id.isNotBlank() && it.id.length <= 256) { "whatsapp_button_id_invalid" }
            require(it.title.isNotBlank() && it.title.length <= 20) { "whatsapp_button_title_invalid" }
        }
    }

    private fun encodeContent(content: WhatsAppOutboundContent): String = buildString {
        append("v1\n").append(content.type.name).append('\n').append(b64(content.body))
        content.buttons.forEach { append('\n').append(b64(it.id)).append(':').append(b64(it.title)) }
    }

    private fun decodeContent(value: String): WhatsAppOutboundContent {
        val lines = value.lines()
        require(lines.size >= 3 && lines[0] == "v1") { "whatsapp_outbox_payload_invalid" }
        return WhatsAppOutboundContent(
            type = WhatsAppOutboundType.valueOf(lines[1]),
            body = unb64(lines[2]),
            buttons = lines.drop(3).filter(String::isNotBlank).map { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "whatsapp_outbox_button_invalid" }
                WhatsAppButton(unb64(line.substring(0, separator)), unb64(line.substring(separator + 1)))
            },
        )
    }

    private fun draftSummary(draft: CareDraftResult): String {
        val fields = draft.fields
        val whenText = fields.startAtLocal?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")) ?: "horário pendente"
        return "Revise antes de confirmar:\n• ${fields.title ?: "Cuidado"}\n• $whenText\n• Pet selecionado no rascunho\n\nNada será criado sem sua confirmação."
    }

    private fun missingLabels(fields: Set<CareDraftField>): String = fields.joinToString(", ") {
        when (it) {
            CareDraftField.PET -> "pet"
            CareDraftField.TYPE -> "tipo de cuidado"
            CareDraftField.TITLE -> "título"
            CareDraftField.START_AT -> "data e horário"
            CareDraftField.SCHEDULE -> "frequência"
            CareDraftField.TIMEZONE -> "fuso horário"
            CareDraftField.RESPONSIBLE -> "responsável"
            else -> it.name.lowercase()
        }
    }

    private fun WhatsAppIdentity.protectedWaId() = ProtectedValue(waIdCiphertext, waIdNonce, encryptionKeyVersion)
    private fun identityAad(identity: WhatsAppIdentity) = "identity:${identity.id.value}:${identity.businessPhoneNumberId}"
    private fun inboxAad(id: UUID, field: String) = "inbox:$id:$field"
    private fun outboxAad(id: UUID) = "outbox:$id:content"
    private fun now(): Instant = clock.now(ZoneOffset.UTC).toInstant()
    private fun requireEnabled() { if (!settings.enabled) throw UpstreamServiceException("Integração com WhatsApp indisponível") }
    private fun requireOwner(access: HouseholdAccess) { if (access.role != HouseholdRole.OWNER) throw ForbiddenException("Apenas proprietários podem gerenciar o WhatsApp") }
    private fun canonicalWaId(value: String): String = value.trim().also {
        require(it.matches(Regex("^[0-9]{5,30}$"))) { "whatsapp_wa_id_invalid" }
    }
    private fun deterministicId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))
    private fun mask(value: String): String = "•••• ${value.takeLast(4)}"
    private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun unb64(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    private fun backoff(attempt: Int): Duration = Duration.ofSeconds(minOf(300L, 5L * (1L shl minOf(attempt - 1, 6))))
    private fun safeErrorCode(exception: Exception): String = (exception.message ?: exception::class.simpleName ?: "WHATSAPP_ERROR")
        .uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(80)

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private val LINK_PATTERN = Regex("(?i)^VINCULAR\\s+(rp_[A-Za-z0-9_-]{40,60})$")
        private val CLAIM_LEASE = Duration.ofMinutes(5)
        private const val MAX_INBOUND_CONTENT = 4_000
    }
}
