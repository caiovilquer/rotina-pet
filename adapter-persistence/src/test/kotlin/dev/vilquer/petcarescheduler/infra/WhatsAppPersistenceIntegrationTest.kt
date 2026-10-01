package dev.vilquer.petcarescheduler.infra

import dev.vilquer.petcarescheduler.infra.adapter.output.external.WhatsAppPersistenceAdapter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ProtectedValue
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundRecord
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboundType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PersistenceTestApplication::class])
@Import(WhatsAppPersistenceAdapter::class)
class WhatsAppPersistenceIntegrationTest : AbstractPostgresIntegrationTest() {
    @Autowired lateinit var whatsapp: WhatsAppPersistenceAdapter

    @Test
    fun `duplicate provider event is acknowledged once and cannot be claimed twice`() {
        val now = Instant.parse("2026-07-14T12:00:00Z")
        val record = WhatsAppInboundRecord(
            id = UUID.randomUUID(),
            providerEventKey = "message:wamid.duplicate",
            providerMessageId = "wamid.duplicate",
            businessPhoneNumberId = "1234567890",
            senderLookupHmac = "a".repeat(64),
            sender = ProtectedValue(byteArrayOf(1, 2, 3), ByteArray(12) { 1 }, 1),
            content = ProtectedValue(byteArrayOf(4, 5, 6), ByteArray(12) { 2 }, 1),
            type = WhatsAppInboundType.TEXT,
            eventAt = now,
            receivedAt = now,
        )

        assertTrue(whatsapp.insert(record))
        assertFalse(whatsapp.insert(record.copy(id = UUID.randomUUID())))
        assertEquals(1, whatsapp.claimInboxBatch(now, 10, Duration.ofMinutes(5)).size)
        assertTrue(whatsapp.claimInboxBatch(now.plusSeconds(1), 10, Duration.ofMinutes(5)).isEmpty())
    }
}
