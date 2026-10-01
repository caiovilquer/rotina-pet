package dev.vilquer.petcarescheduler.infra.adapter.output.whatsapp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboundType
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID

data class MetaWhatsAppSettings(
    val graphApiVersion: String,
    val systemUserToken: String,
    val phoneNumberId: String,
) {
    init {
        require(graphApiVersion.matches(Regex("^v[0-9]{1,2}\\.[0-9]$"))) { "meta_graph_version_invalid" }
        require(systemUserToken.isNotBlank()) { "meta_system_user_token_missing" }
        require(phoneNumberId.matches(Regex("^[0-9]{5,30}$"))) { "meta_phone_number_id_invalid" }
    }
}

class MetaWhatsAppGatewayAdapter(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val settings: MetaWhatsAppSettings,
) : WhatsAppGatewayPort {

    override fun send(message: WhatsAppGatewayMessage): String {
        require(message.businessPhoneNumberId == settings.phoneNumberId) { "meta_phone_number_mismatch" }
        val payload = linkedMapOf<String, Any>(
            "messaging_product" to "whatsapp",
            "recipient_type" to "individual",
            "to" to message.recipientWaId,
        )
        when (message.content.type) {
            WhatsAppOutboundType.TEXT -> {
                payload["type"] = "text"
                payload["text"] = mapOf("preview_url" to false, "body" to message.content.body)
            }
            WhatsAppOutboundType.INTERACTIVE -> {
                payload["type"] = "interactive"
                payload["interactive"] = mapOf(
                    "type" to "button",
                    "body" to mapOf("text" to message.content.body),
                    "action" to mapOf(
                        "buttons" to message.content.buttons.map {
                            mapOf("type" to "reply", "reply" to mapOf("id" to it.id, "title" to it.title))
                        },
                    ),
                )
            }
        }
        val response = restClient.post()
            .uri("https://graph.facebook.com/{version}/{phoneNumberId}/messages", settings.graphApiVersion, settings.phoneNumberId)
            .headers { it.setBearerAuth(settings.systemUserToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("meta_empty_response")
        val root: JsonNode = objectMapper.readTree(response)
        return root.path("messages").path(0).path("id").asText().takeIf(String::isNotBlank)
            ?: throw IllegalStateException("meta_message_id_missing")
    }
}

class FakeWhatsAppGatewayAdapter : WhatsAppGatewayPort {
    override fun send(message: WhatsAppGatewayMessage): String {
        require(message.recipientWaId.matches(Regex("^[0-9]{5,30}$"))) { "fake_whatsapp_recipient_invalid" }
        return "wamid.fake.${UUID.randomUUID()}"
    }
}
