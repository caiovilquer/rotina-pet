package dev.vilquer.petcarescheduler.infra.adapter.output.mail

import com.fasterxml.jackson.databind.ObjectMapper
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.entity.Event
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.infra.adapter.output.notification.EmailNotificationAdapter
import dev.vilquer.petcarescheduler.infra.config.MailApiProps
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareEscalationNotificationTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderNotificationTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EventReminderTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renderiza os cinco e-mails transacionais com dados de exemplo, valida o
 * conteúdo essencial (links, escaping, marca) e grava cada HTML em
 * build/email-previews/ para inspeção visual no navegador.
 */
class EmailTemplatesTest {

    private val frontBase = "https://rotinapet.vilquer.dev"
    private val props = MailApiProps(from = "no-reply@rotinapet.vilquer.dev", fromName = "RotinaPet")
    private val mapper = ObjectMapper()
    private val captured = mutableListOf<String>()

    private fun client(): RestClient {
        val builder = RestClient.builder().baseUrl("https://mail.test/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.manyTimes(), requestTo("https://mail.test/v1/email"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request -> captured.add((request as MockClientHttpRequest).bodyAsString) }
            .andRespond(withSuccess())
        return builder.build()
    }

    private fun lastPayload(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(captured.last(), Map::class.java) as Map<String, Any?>
    }

    private fun savePreview(name: String, html: String) {
        val dir = Path.of("build", "email-previews")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.html"), html)
    }

    @Test
    fun `password reset email carries link, ttl and brand shell`() {
        MailSenderAdapter(client(), props, frontBase)
            .sendResetLink(Email("caio@example.com"), "tok-123", Duration.ofMinutes(30), null)

        val payload = lastPayload()
        val html = payload["html"] as String
        assertThat(html).contains("$frontBase/auth/reset-password?token=tok-123")
        assertThat(html).contains("30 minutos")
        assertThat(html).contains("$frontBase/email/simbolo.png")
        assertThat(payload["text"] as String).contains("tok-123")
        savePreview("password-reset", html)
    }

    @Test
    fun `invitation email escapes names and shows role`() {
        HouseholdInvitationMailAdapter(client(), props, frontBase).sendInvitation(
            email = "ana@example.com",
            householdName = "Quintal <da> Serra & Cia",
            inviterName = "Caio",
            role = HouseholdRole.OWNER,
            token = "invite tok/+",
            expiresAt = Instant.now().plus(Duration.ofDays(7)),
        )

        val payload = lastPayload()
        val html = payload["html"] as String
        assertThat(html).contains("Quintal &lt;da&gt; Serra &amp; Cia")
        assertThat(html).doesNotContain("Quintal <da>")
        assertThat(html).contains("$frontBase/invite?token=invite+tok%2F%2B")
        assertThat(html).contains("acesso administrativo completo")
        savePreview("household-invitation", html)
    }

    @Test
    fun `event reminder email renders type badge and schedule`() {
        val adapter = EmailNotificationAdapter(client(), props, "America/Sao_Paulo", frontBase)
        val ok = adapter.sendEventReminder(
            EventReminderTarget(
                event = Event(
                    type = EventType.MEDICINE,
                    description = "Meio comprimido de Apoquel junto com a ração",
                    dateStart = LocalDateTime.of(2026, 7, 14, 8, 0),
                    recurrence = null,
                    petId = null,
                ),
                tutorEmail = "caio@example.com",
                petName = "Nina",
            )
        )

        assertThat(ok).isTrue()
        val payload = lastPayload()
        val html = payload["html"] as String
        assertThat(html).contains("Medicação")
        assertThat(html).contains("Nina")
        assertThat(html).contains("$frontBase/today")
        assertThat(payload["text"] as String).contains("Nina")
        savePreview("event-reminder", html)
    }

    @Test
    fun `care reminder email uses shared reminder template`() {
        val adapter = EmailNotificationAdapter(client(), props, "America/Sao_Paulo", frontBase)
        val ok = adapter.sendCareReminder(
            CareReminderNotificationTarget(
                occurrenceId = CareOccurrenceId(UUID.randomUUID()),
                type = EventType.VACCINE,
                title = "Reforço da V10",
                dueAt = java.time.Instant.parse("2026-07-20T13:30:00Z"),
                tutorEmail = "caio@example.com",
                petName = "Thor",
            )
        )

        assertThat(ok).isTrue()
        val html = lastPayload()["html"] as String
        assertThat(html).contains("Vacinação")
        assertThat(html).contains("Thor")
        savePreview("care-reminder", html)
    }

    @Test
    fun `care escalation email renders critical tone`() {
        val adapter = EmailNotificationAdapter(client(), props, "America/Sao_Paulo", frontBase)
        val ok = adapter.sendCareEscalation(
            CareEscalationNotificationTarget(
                recipientEmail = "caio@example.com",
                petName = "Nina",
                careTitle = "Insulina da manhã",
                dueAt = java.time.Instant.parse("2026-07-13T10:00:00Z"),
            )
        )

        assertThat(ok).isTrue()
        val payload = lastPayload()
        val html = payload["html"] as String
        assertThat(html).contains("Insulina da manhã")
        assertThat(html).contains(RotinaPetEmail.ERROR)
        assertThat(html).contains("$frontBase/today")
        assertThat(payload["subject"] as String).contains("Nina")
        savePreview("care-escalation", html)
    }
}
