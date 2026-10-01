package dev.vilquer.petcarescheduler.infra.adapter.output.notification


import dev.vilquer.petcarescheduler.core.domain.entity.Event
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EventReminderTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderNotificationTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareEscalationNotificationTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.NotificationPort
import dev.vilquer.petcarescheduler.infra.adapter.output.mail.RotinaPetEmail
import dev.vilquer.petcarescheduler.infra.config.MailApiProps
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.RestClient
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdTimezone

@Component
class EmailNotificationAdapter(
    // Qualificado explicitamente: desde a adição do googlePlacesClient, há
    // dois beans RestClient no contexto.
    @param:Qualifier("mailerSendClient") private val http: RestClient,
    private val props: MailApiProps,
    @param:Value("\${app.timezone}") private val timezone: String,
    @param:Value("\${app.frontend.base-url:https://rotinapet.vilquer.dev}") private val frontendBaseUrl: String,
) : NotificationPort {

    private val log = LoggerFactory.getLogger(EmailNotificationAdapter::class.java)

    override fun sendEventReminder(target: EventReminderTarget): Boolean {
        val event = target.event
        val zoneId = HouseholdTimezone.parse(target.timezone)
        val html = renderTemplate(event, target.petName, zoneId)
        val subject = "Lembrete do RotinaPet: ${event.type.pt()}"

        val payload = mapOf(
            "from" to mapOf("email" to props.from, "name" to props.fromName),
            "to" to listOf(mapOf("email" to target.tutorEmail)),
            "subject" to subject,
            "text" to reminderText(event.type, event.description, event.dateStart, target.petName, zoneId),
            "html" to html
        )

        return try {
            val status =
                http.post().uri("/email").body(payload).retrieve().onStatus({ s -> s.value() >= 400 }) { _, resp ->
                    val body = resp.body.bufferedReader().use { it.readText() }
                    throw IllegalStateException("Mail Send error ${resp.statusCode.value()}: $body")
                }.toBodilessEntity().statusCode

            val ok = status.is2xxSuccessful || status == HttpStatus.ACCEPTED
            if (ok) {
                log.info("Sent mail (API) for event {} to {}", event.id, target.tutorEmail)
            } else {
                log.error("Mail Send returned status {} for event {}", status.value(), event.id)
            }
            ok
        } catch (ex: Exception) {
            log.error("Failed to call Mail Send API for event {}", event.id, ex)
            false
        }
    }

    override fun sendCareReminder(target: CareReminderNotificationTarget): Boolean {
        val zoneId = HouseholdTimezone.parse(target.timezone)
        val html = renderTemplate(target.type, target.title, target.dueAt, target.petName, zoneId)
        val payload = mapOf(
            "from" to mapOf("email" to props.from, "name" to props.fromName),
            "to" to listOf(mapOf("email" to target.tutorEmail)),
            "subject" to "Lembrete do RotinaPet: ${target.type.pt()}",
            "text" to reminderText(target.type, target.title, target.dueAt, target.petName, zoneId),
            "html" to html,
        )
        return try {
            val status = http.post().uri("/email").body(payload).retrieve()
                .onStatus({ it.value() >= 400 }) { _, response ->
                    val body = response.body.bufferedReader().use { it.readText() }
                    throw IllegalStateException("Mail Send error ${response.statusCode.value()}: $body")
                }.toBodilessEntity().statusCode
            status.is2xxSuccessful || status == HttpStatus.ACCEPTED
        } catch (ex: Exception) {
            log.error("Failed to send care reminder for occurrence {}", target.occurrenceId.value, ex)
            false
        }
    }

    override fun sendCareEscalation(target: CareEscalationNotificationTarget): Boolean {
        val whenText = target.dueAt.atZone(HouseholdTimezone.parse(target.timezone)).format(DATEFMT).replaceFirstChar { it.titlecase(PTBR) }
        val safeTitle = RotinaPetEmail.escape(target.careTitle)
        val safePet = RotinaPetEmail.escape(target.petName)
        val ctaUrl = "${frontendBaseUrl.trimEnd('/')}/today"
        val content = buildString {
            append(RotinaPetEmail.badge("Cuidado crítico", RotinaPetEmail.ERROR, RotinaPetEmail.ERROR_BG))
            append(RotinaPetEmail.title("Um cuidado crítico ainda não foi confirmado"))
            append(
                RotinaPetEmail.paragraph(
                    "O cuidado <strong>$safeTitle</strong> de <strong>$safePet</strong> continua pendente e foi marcado como crítico pela família."
                )
            )
            append(
                RotinaPetEmail.detailCard(
                    listOf(
                        "Pet" to safePet,
                        "Cuidado" to safeTitle,
                        "Previsto para" to whenText,
                    )
                )
            )
            append(
                RotinaPetEmail.notice(
                    "Confirme o cuidado assim que possível — ou combine com a família quem vai assumir.",
                    RotinaPetEmail.ERROR,
                    RotinaPetEmail.ERROR_BG,
                )
            )
            append(RotinaPetEmail.ctaButton("Ver cuidado agora", ctaUrl, RotinaPetEmail.ERROR))
            append(RotinaPetEmail.divider())
            append(RotinaPetEmail.mutedNote("Este alerta foi configurado por um proprietário da família no RotinaPet."))
        }
        val html = RotinaPetEmail.render(
            docTitle = "Cuidado crítico pendente",
            preheader = "$safeTitle de $safePet, previsto para $whenText, continua pendente.",
            contentHtml = content,
            footerReason = "Você recebeu este alerta porque faz parte de uma família com escalonamento de cuidados críticos ativado no RotinaPet.",
            baseUrl = frontendBaseUrl,
        )
        val text = """
            Cuidado crítico pendente — RotinaPet

            O cuidado "${target.careTitle}" de ${target.petName}, previsto para $whenText, continua pendente.
            Confirme em: $ctaUrl

            Este alerta foi configurado por um proprietário da família no RotinaPet.
        """.trimIndent()
        val payload = mapOf(
            "from" to mapOf("email" to props.from, "name" to props.fromName),
            "to" to listOf(mapOf("email" to target.recipientEmail)),
            "subject" to "Atenção: cuidado crítico de ${target.petName} está pendente",
            "text" to text, "html" to html,
        )
        return try {
            http.post().uri("/email").body(payload).retrieve()
                .onStatus({ it.value() >= 400 }) { _, response ->
                    throw IllegalStateException("Mail Send error ${response.statusCode.value()}")
                }.toBodilessEntity().statusCode.is2xxSuccessful
        } catch (ex: Exception) {
            log.error("Failed to send critical care escalation", ex)
            false
        }
    }

    // ===== apresentação =====

    private val PTBR: Locale = Locale.of("pt", "BR")
    private val DEFAULTZONE = parseZoneId(timezone)
    private val DATEFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy 'às' HH:mm", PTBR)

    private fun EventType?.pt(): String = when (this) {
        EventType.VACCINE -> "Vacinação"
        EventType.MEDICINE -> "Medicação"
        EventType.DIARY -> "Diário"
        EventType.BREED -> "Reprodução"
        EventType.SERVICE -> "Serviço"
        else -> "Evento"
    }

    /** Cores de badge por tipo de evento — espelham os tokens --q-ev-* do frontend. */
    private fun EventType?.badgeColors(): Pair<String, String> = when (this) {
        EventType.VACCINE -> "#2F7A56" to "#E3F0E7"
        EventType.MEDICINE -> "#B34A38" to "#F7E5E0"
        EventType.DIARY -> "#3E7CA6" to "#E3EDF5"
        EventType.BREED -> "#B04A72" to "#F6E4EC"
        EventType.SERVICE -> "#8F6A12" to "#F6ECD2"
        else -> RotinaPetEmail.GREEN to "#E3F0E7"
    }

    private fun renderTemplate(event: Event, petNameRaw: String?, zoneId: ZoneId = DEFAULTZONE): String {
        return renderTemplate(event.type, event.description, event.dateStart, petNameRaw, zoneId)
    }

    private fun renderTemplate(
        type: EventType,
        descriptionRaw: String?,
        dueAt: java.time.Instant,
        petNameRaw: String?,
        zoneId: ZoneId,
    ) = renderTemplate(type, descriptionRaw, dueAt.atZone(zoneId).toLocalDateTime(), petNameRaw, zoneId)

    private fun renderTemplate(
        type: EventType,
        descriptionRaw: String?,
        dateStart: java.time.LocalDateTime,
        petNameRaw: String?,
        zoneId: ZoneId = DEFAULTZONE,
    ): String {
        val tipo = type.pt()
        val dataStr = dateStart.atZone(zoneId).format(DATEFMT).replaceFirstChar { it.titlecase(PTBR) }

        val petName = petNameRaw?.let { RotinaPetEmail.escape(it) } ?: "Pet"
        val descricao = descriptionRaw?.takeIf { it.isNotBlank() }?.let { RotinaPetEmail.escape(it) }
        val ctaUrl = "${frontendBaseUrl.trimEnd('/')}/today"
        val (badgeFg, badgeBg) = type.badgeColors()

        val content = buildString {
            append(RotinaPetEmail.badge(tipo, badgeFg, badgeBg))
            append(RotinaPetEmail.title("Hora de cuidar de $petName"))
            append(
                RotinaPetEmail.paragraph(
                    "Passando para lembrar de um cuidado que faz parte da rotina de <strong>$petName</strong>:"
                )
            )
            append(
                RotinaPetEmail.detailCard(
                    buildList {
                        add("Pet" to petName)
                        add("Quando" to dataStr)
                        descricao?.let { add("Detalhes" to it) }
                    }
                )
            )
            append(RotinaPetEmail.ctaButton("Ver na agenda de hoje", ctaUrl))
            append(RotinaPetEmail.divider())
            append(RotinaPetEmail.mutedNote("Depois de concluir, confirme o cuidado no app para manter o histórico de $petName em dia."))
        }
        return RotinaPetEmail.render(
            docTitle = "Lembrete: $tipo",
            preheader = "$tipo de $petName — $dataStr.",
            contentHtml = content,
            footerReason = "Você recebeu este lembrete porque cadastrou um plano de cuidado no RotinaPet.",
            baseUrl = frontendBaseUrl,
        )
    }

    private fun reminderText(
        type: EventType,
        description: String?,
        dateStart: java.time.LocalDateTime,
        petName: String?,
        zoneId: ZoneId = DEFAULTZONE,
    ): String {
        val dataStr = dateStart.atZone(zoneId).format(DATEFMT).replaceFirstChar { it.titlecase(PTBR) }
        val detalhes = description?.takeIf { it.isNotBlank() }?.let { "\nDetalhes: $it" }.orEmpty()
        return """
            Lembrete do RotinaPet: ${type.pt()}

            Pet: ${petName ?: "Pet"}
            Quando: $dataStr$detalhes

            Veja na agenda: ${frontendBaseUrl.trimEnd('/')}/today
        """.trimIndent()
    }

    private fun reminderText(
        type: EventType,
        description: String?,
        dueAt: java.time.Instant,
        petName: String?,
        zoneId: ZoneId,
    ) = reminderText(type, description, dueAt.atZone(zoneId).toLocalDateTime(), petName, zoneId)

    private fun parseZoneId(value: String): ZoneId =
        try {
            ZoneId.of(value)
        } catch (ex: Exception) {
            HouseholdTimezone.parse(null)
        }
}







