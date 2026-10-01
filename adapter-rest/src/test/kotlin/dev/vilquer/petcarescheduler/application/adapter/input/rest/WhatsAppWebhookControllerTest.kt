package dev.vilquer.petcarescheduler.application.adapter.input.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.application.service.RateLimiterService
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppWebhookSecurityPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppInboundUseCase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets

class WhatsAppWebhookControllerTest {
    private val security = mock<WhatsAppWebhookSecurityPort>()
    private val inbound = mock<WhatsAppInboundUseCase>()
    private val request = mock<HttpServletRequest>()
    private val limiter = RateLimiterService(
        dev.vilquer.petcarescheduler.application.service.RateLimitProperties(),
        object : RateLimitStorePort {
            override fun registerAttempt(key: String, now: java.time.Instant, window: java.time.Duration) = 1
            override fun delete(key: String) = Unit
            override fun deleteOlderThan(cutoff: java.time.Instant) = 0
        },
    )
    private val controller = WhatsAppWebhookController(
        security, inbound, ObjectMapper(), limiter, SimpleMeterRegistry(),
    )

    @Test
    fun `normalizes official text and status payload after signature validation`() {
        val raw = PAYLOAD.toByteArray(StandardCharsets.UTF_8)
        whenever(security.verifySignature(raw, "sha256=valid")).thenReturn(true)
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(inbound.ingest(org.mockito.kotlin.any())).thenReturn(true)

        val response = controller.receive(raw, "sha256=valid", request)

        assertEquals(200, response.statusCode.value())
        val commands = argumentCaptor<dev.vilquer.petcarescheduler.usecase.command.IngestWhatsAppEventCommand>()
        verify(inbound, org.mockito.kotlin.times(2)).ingest(commands.capture())
        assertEquals(WhatsAppInboundType.TEXT, commands.firstValue.type)
        assertEquals("message:wamid.inbound", commands.firstValue.providerEventKey)
        assertEquals("Dar remédio amanhã às 9h", commands.firstValue.content)
        assertEquals(WhatsAppInboundType.STATUS, commands.secondValue.type)
        assertEquals("delivered", commands.secondValue.providerStatus)
    }

    @Test
    fun `rejects invalid signature before parsing malformed json`() {
        val raw = "not-json".toByteArray(StandardCharsets.UTF_8)
        whenever(security.verifySignature(raw, "sha256=invalid")).thenReturn(false)

        assertThrows(ResponseStatusException::class.java) {
            controller.receive(raw, "sha256=invalid", request)
        }
        verify(inbound, never()).ingest(org.mockito.kotlin.any())
    }

    companion object {
        private val PAYLOAD = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "changes": [{
                  "field": "messages",
                  "value": {
                    "metadata": {"phone_number_id": "1234567890"},
                    "messages": [{
                      "from": "5511999999999",
                      "id": "wamid.inbound",
                      "timestamp": "1784030400",
                      "type": "text",
                      "text": {"body": "Dar remédio amanhã às 9h"}
                    }],
                    "statuses": [{
                      "id": "wamid.outbound",
                      "recipient_id": "5511999999999",
                      "status": "delivered",
                      "timestamp": "1784030401"
                    }]
                  }
                }]
              }]
            }
        """.trimIndent()
    }
}
