package dev.vilquer.petcarescheduler.application.adapter.input.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.application.service.RateLimitAction
import dev.vilquer.petcarescheduler.application.service.RateLimiterService
import dev.vilquer.petcarescheduler.usecase.command.IngestWhatsAppEventCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppWebhookSecurityPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppInboundUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/v1/webhooks/whatsapp")
class WhatsAppWebhookController(
    private val security: WhatsAppWebhookSecurityPort,
    private val whatsapp: WhatsAppInboundUseCase,
    private val objectMapper: ObjectMapper,
    private val rateLimiter: RateLimiterService,
    private val meters: MeterRegistry,
) {
    @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun verify(
        @RequestParam(name = "hub.mode") mode: String,
        @RequestParam(name = "hub.verify_token") verifyToken: String,
        @RequestParam(name = "hub.challenge") challenge: String,
    ): ResponseEntity<String> {
        if (mode != "subscribe" || !security.verifyChallenge(verifyToken)) {
            meters.counter(METRIC, "outcome", "challenge_rejected").increment()
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        meters.counter(METRIC, "outcome", "challenge_ok").increment()
        return ResponseEntity.ok(challenge)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody rawBody: ByteArray,
        @RequestHeader(name = "X-Hub-Signature-256", required = false) signature: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (rawBody.isEmpty() || rawBody.size > MAX_BODY_BYTES) {
            meters.counter(METRIC, "outcome", "body_rejected").increment()
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        }
        if (!security.verifySignature(rawBody, signature)) {
            meters.counter(METRIC, "outcome", "signature_rejected").increment()
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        rateLimiter.check(RateLimitAction.WHATSAPP_WEBHOOK, request.remoteAddr ?: "unknown")
        val events = parse(objectMapper.readTree(rawBody))
        var inserted = 0
        events.forEach { if (whatsapp.ingest(it)) inserted++ }
        meters.counter(METRIC, "outcome", if (inserted == 0) "duplicate_or_empty" else "accepted").increment()
        return ResponseEntity.ok().build()
    }

    private fun parse(root: JsonNode): List<IngestWhatsAppEventCommand> {
        if (root.path("object").asText() != "whatsapp_business_account") return emptyList()
        return buildList {
            root.path("entry").forEach { entry ->
                entry.path("changes").forEach { change ->
                    if (change.path("field").asText() != "messages") return@forEach
                    val value = change.path("value")
                    val phoneNumberId = value.path("metadata").path("phone_number_id").asText()
                    if (!phoneNumberId.matches(Regex("^[0-9]{5,30}$"))) return@forEach
                    value.path("messages").forEach { message ->
                        parseMessage(message, phoneNumberId)?.let(::add)
                    }
                    value.path("statuses").forEach { status ->
                        parseStatus(status, phoneNumberId)?.let(::add)
                    }
                }
            }
        }
    }

    private fun parseMessage(message: JsonNode, phoneNumberId: String): IngestWhatsAppEventCommand? {
        val id = message.path("id").asText().takeIf(String::isNotBlank) ?: return null
        val sender = message.path("from").asText().takeIf(String::isNotBlank) ?: return null
        val type = message.path("type").asText()
        val normalized = when (type) {
            "text" -> WhatsAppInboundType.TEXT to message.path("text").path("body").asText()
            "interactive" -> {
                val interactive = message.path("interactive")
                val button = interactive.path("button_reply").path("id").asText()
                    .ifBlank { interactive.path("list_reply").path("id").asText() }
                WhatsAppInboundType.INTERACTIVE to button
            }
            else -> return null
        }
        if (normalized.second.isBlank()) return null
        return IngestWhatsAppEventCommand(
            providerEventKey = "message:$id",
            providerMessageId = id,
            businessPhoneNumberId = phoneNumberId,
            senderWaId = sender,
            type = normalized.first,
            content = normalized.second,
            eventAt = epoch(message.path("timestamp").asText()),
        )
    }

    private fun parseStatus(status: JsonNode, phoneNumberId: String): IngestWhatsAppEventCommand? {
        val id = status.path("id").asText().takeIf(String::isNotBlank) ?: return null
        val state = status.path("status").asText().takeIf(String::isNotBlank) ?: return null
        val timestamp = status.path("timestamp").asText()
        return IngestWhatsAppEventCommand(
            providerEventKey = "status:$id:$state:$timestamp",
            providerMessageId = id,
            businessPhoneNumberId = phoneNumberId,
            senderWaId = status.path("recipient_id").asText().takeIf(String::isNotBlank),
            type = WhatsAppInboundType.STATUS,
            content = null,
            providerStatus = state,
            eventAt = epoch(timestamp),
        )
    }

    private fun epoch(value: String): Instant = value.toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.EPOCH

    companion object {
        private const val MAX_BODY_BYTES = 1_048_576
        private const val METRIC = "rotinapet.whatsapp.webhook"
    }
}
