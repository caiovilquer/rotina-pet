package dev.vilquer.petcarescheduler.infra.adapter.output.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppButton
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundContent
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class MetaWhatsAppGatewayAdapterTest {
    @Test
    fun `sends official reply button contract and returns wamid`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = MetaWhatsAppGatewayAdapter(
            builder.build(),
            ObjectMapper(),
            MetaWhatsAppSettings("v23.0", "secret-token", "1234567890"),
        )
        server.expect(requestTo("https://graph.facebook.com/v23.0/1234567890/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer secret-token"))
            .andExpect(content().json("""
                {
                  "messaging_product":"whatsapp",
                  "recipient_type":"individual",
                  "to":"5511999999999",
                  "type":"interactive",
                  "interactive":{
                    "type":"button",
                    "body":{"text":"Revise o rascunho"},
                    "action":{"buttons":[{"type":"reply","reply":{"id":"draft.confirm|id|1","title":"Confirmar"}}]}
                  }
                }
            """.trimIndent()))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.accepted\"}]}", MediaType.APPLICATION_JSON))

        val id = adapter.send(
            WhatsAppGatewayMessage(
                "1234567890",
                "5511999999999",
                WhatsAppOutboundContent(
                    WhatsAppOutboundType.INTERACTIVE,
                    "Revise o rascunho",
                    listOf(WhatsAppButton("draft.confirm|id|1", "Confirmar")),
                ),
            ),
        )

        assertEquals("wamid.accepted", id)
        server.verify()
    }
}
