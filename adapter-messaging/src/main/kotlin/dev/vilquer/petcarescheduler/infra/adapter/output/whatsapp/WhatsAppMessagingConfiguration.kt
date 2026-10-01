package dev.vilquer.petcarescheduler.infra.adapter.output.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient

@Configuration
class WhatsAppMessagingConfiguration {

    @Bean
    fun whatsappSecurityAdapter(environment: Environment): WhatsAppSecurityAdapter {
        val material = WhatsAppSecurityMaterial(
            webhookVerifyToken = environment.getProperty("app.whatsapp.webhook-verify-token", ""),
            appSecret = environment.getProperty("app.whatsapp.app-secret", ""),
            currentHmacKey = WhatsAppSecurityAdapter.decodeKey(environment.getProperty("app.whatsapp.hmac-key-current")),
            previousHmacKey = WhatsAppSecurityAdapter.decodeKey(environment.getProperty("app.whatsapp.hmac-key-previous")),
            currentEncryptionKey = WhatsAppSecurityAdapter.decodeKey(environment.getProperty("app.whatsapp.encryption-key-current")),
            previousEncryptionKey = WhatsAppSecurityAdapter.decodeKey(environment.getProperty("app.whatsapp.encryption-key-previous")),
            currentKeyVersion = environment.getProperty("app.whatsapp.key-version-current", Int::class.java, 1),
            previousKeyVersion = environment.getProperty("app.whatsapp.key-version-previous")?.toIntOrNull(),
        )
        if (environment.getProperty("app.whatsapp.enabled", Boolean::class.java, false)) material.validateEnabled()
        return WhatsAppSecurityAdapter(material)
    }

    @Bean
    fun whatsAppGatewayPort(environment: Environment, objectMapper: ObjectMapper): WhatsAppGatewayPort {
        return when (environment.getProperty("app.whatsapp.provider", "fake").lowercase()) {
            "fake" -> FakeWhatsAppGatewayAdapter()
            "meta" -> MetaWhatsAppGatewayAdapter(
                RestClient.builder().build(),
                objectMapper,
                MetaWhatsAppSettings(
                    graphApiVersion = environment.getProperty("app.whatsapp.graph-api-version", ""),
                    systemUserToken = environment.getProperty("app.whatsapp.system-user-token", ""),
                    phoneNumberId = environment.getProperty("app.whatsapp.business-phone-number-id", ""),
                ),
            )
            else -> throw IllegalArgumentException("whatsapp_provider_invalid")
        }
    }
}
