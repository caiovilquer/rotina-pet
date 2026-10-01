package dev.vilquer.petcarescheduler.infra

import com.fasterxml.jackson.databind.JsonNode
import dev.vilquer.petcarescheduler.PetCareSchedulerApplication
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.ReminderOutboxJpaRepository
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.DispatchPendingRemindersUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.SendDailyRemindersUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.RegisterEventUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.KnowledgeIndexUseCase
import dev.vilquer.petcarescheduler.usecase.command.RegisterEventCommand
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordAttachment
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordId
import dev.vilquer.petcarescheduler.core.domain.media.MediaAsset
import dev.vilquer.petcarescheduler.core.domain.media.MediaPurpose
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordAttachmentRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.MediaAssetRepositoryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Rede de segurança da Fase 1: exercita o fluxo completo da API com o contexto
 * real (PostgreSQL/pgvector + Flyway + JWT). Se o wiring de qualquer bean sumir durante a
 * migração de módulos, este teste quebra imediatamente.
 */
@SpringBootTest(
    classes = [PetCareSchedulerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.ssl.enabled=false",
        "app.mail.from=noreply@test.local",
        "app.seed.enabled=false",
    ],
)
@ActiveProfiles("dev")
class SmokeE2ETest : AbstractPostgresIntegrationTest() {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var sendDailyReminders: SendDailyRemindersUseCase

    @Autowired
    lateinit var dispatchPendingReminders: DispatchPendingRemindersUseCase

    @Autowired
    lateinit var reminderOutboxRepository: ReminderOutboxJpaRepository

    @Autowired
    lateinit var registerLegacyEvent: RegisterEventUseCase

    @Autowired
    lateinit var mediaAssets: MediaAssetRepositoryPort

    @Autowired
    lateinit var healthAttachments: HealthRecordAttachmentRepositoryPort

    @Autowired
    lateinit var knowledgeIndex: KnowledgeIndexUseCase

    private fun jsonHeaders(token: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        token?.let { setBearerAuth(it) }
    }

    private fun refreshCookieValue(response: org.springframework.http.ResponseEntity<*>): String {
        val setCookie = response.headers["Set-Cookie"]?.firstOrNull { it.startsWith("refresh_token=") }
            ?: error("no refresh_token cookie in response: ${response.headers}")
        return setCookie.substringAfter("refresh_token=").substringBefore(";")
    }

    private fun cookieHeaders(cookieValue: String) = HttpHeaders().apply {
        set("Cookie", "refresh_token=$cookieValue")
    }

    @Test
    fun `assistant draft is reviewed and confirmed exactly once`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val email = "bia.assistant.$suffix@example.com"
        val signup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Bia","lastName":"Souza","email":"$email","rawPassword":"s3cret-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, signup.statusCode, "signup assistant: ${signup.body}")
        val login = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"$email","password":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode, "login assistant: ${login.body}")
        val token = login.body!!["token"].asText()
        val pet = rest.postForEntity(
            "/api/v1/pets",
            HttpEntity(
                """{"name":"Mel","species":"Dog","breed":"SRD","birthdate":"2021-01-01"}""",
                jsonHeaders(token),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, pet.statusCode, "pet assistant: ${pet.body}")
        val petId = pet.body!!["petId"].asLong()
        val start = LocalDateTime.now(ZoneId.of("America/Sao_Paulo")).plusDays(1).withHour(8).withMinute(0).truncatedTo(ChronoUnit.MINUTES)
        val requestId = UUID.randomUUID()
        val generated = rest.postForEntity(
            "/api/v1/assistant/care-drafts",
            HttpEntity(
                """{"requestId":"$requestId","instruction":"Vacina da Mel em ${start.toLocalDate()} às 08:00"}""",
                jsonHeaders(token),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, generated.statusCode, "generate draft: ${generated.body}")
        assertEquals("READY", generated.body!!["status"].asText(), "draft: ${generated.body}")
        assertEquals(petId, generated.body!!["fields"]["petId"].asLong())
        assertEquals("EXPLICIT", generated.body!!["provenance"]["PET"].asText())
        val draftId = generated.body!!["id"].asText()
        val draftVersion = generated.body!!["version"].asLong()
        val confirmBody = """{"requestId":"${UUID.randomUUID()}","expectedVersion":$draftVersion}"""

        val confirmed = rest.postForEntity(
            "/api/v1/assistant/care-drafts/$draftId/confirm",
            HttpEntity(confirmBody, jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, confirmed.statusCode, "confirm draft: ${confirmed.body}")
        assertEquals("CONFIRMED", confirmed.body!!["draft"]["status"].asText())
        val planId = confirmed.body!!["plan"]["id"].asText()

        val replay = rest.postForEntity(
            "/api/v1/assistant/care-drafts/$draftId/confirm",
            HttpEntity(confirmBody, jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, replay.statusCode, "confirm replay: ${replay.body}")
        assertEquals(planId, replay.body!!["plan"]["id"].asText())
    }

    @Test
    fun `assistant answers structured and grounded questions without cross household evidence`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val firstEmail = "history.$suffix@example.com"
        val firstSignup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity("""{"firstName":"Lia","lastName":"Moraes","email":"$firstEmail","rawPassword":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, firstSignup.statusCode)
        val firstLogin = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"$firstEmail","password":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val firstToken = firstLogin.body!!["token"].asText()
        val firstPet = rest.postForEntity(
            "/api/v1/pets",
            HttpEntity("""{"name":"Luna","species":"Cat","breed":"SRD","birthdate":"2022-01-01"}""", jsonHeaders(firstToken)),
            JsonNode::class.java,
        )
        val firstPetId = firstPet.body!!["petId"].asLong()
        val record = rest.postForEntity(
            "/api/v1/pets/$firstPetId/health-records",
            HttpEntity(
                """{"type":"VACCINE","occurredAt":"${Instant.now().minusSeconds(3600)}","title":"Vacina antirrábica","notes":"Após a vacina, Luna bebeu água normalmente durante todo o dia.","productName":"Produto registrado"}""",
                jsonHeaders(firstToken),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, record.statusCode, "health record: ${record.body}")
        knowledgeIndex.processBatch()
        val sources = rest.exchange(
            "/api/v1/assistant/knowledge-sources?petId=$firstPetId",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders(firstToken)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, sources.statusCode, "knowledge sources: ${sources.body}")
        assertEquals("READY", sources.body!![0]["status"].asText(), "knowledge sources: ${sources.body}")

        val structured = rest.postForEntity(
            "/api/v1/assistant/questions",
            HttpEntity("""{"petId":$firstPetId,"question":"Quando foi a última vacina?"}""", jsonHeaders(firstToken)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, structured.statusCode, "structured answer: ${structured.body}")
        assertEquals("STRUCTURED", structured.body!!["kind"].asText())
        assertTrue(structured.body!!["citations"].size() > 0)

        val grounded = rest.postForEntity(
            "/api/v1/assistant/questions",
            HttpEntity("""{"petId":$firstPetId,"question":"O que a nota registra sobre hidratação e água?"}""", jsonHeaders(firstToken)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, grounded.statusCode, "grounded answer: ${grounded.body}")
        assertEquals("RAG", grounded.body!!["kind"].asText())
        assertEquals(false, grounded.body!!["insufficientEvidence"].asBoolean(), "grounded answer: ${grounded.body}")
        assertEquals(record.body!!["id"].asText(), grounded.body!!["citations"][0]["resourceId"].asText())

        val secondEmail = "history.other.$suffix@example.com"
        rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity("""{"firstName":"Nina","lastName":"Costa","email":"$secondEmail","rawPassword":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val secondLogin = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"$secondEmail","password":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val crossTenant = rest.postForEntity(
            "/api/v1/assistant/questions",
            HttpEntity("""{"petId":$firstPetId,"question":"O que consta na nota?"}""", jsonHeaders(secondLogin.body!!["token"].asText())),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.statusCode)
    }

    @Test
    fun `full journey - signup, login, pet, care plan, safe completion, undo and delete`() {
        // --- signup ---
        val signup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Ana","lastName":"Silva","email":"ana.smoke@example.com","rawPassword":"s3cret-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, signup.statusCode, "signup: ${signup.body}")
        assertTrue(signup.body!!["tutorId"].asLong() > 0)

        // --- login ---
        val login = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"ana.smoke@example.com","password":"s3cret-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode, "login: ${login.body}")
        val token = login.body!!["token"].asText()
        assertTrue(token.isNotBlank())

        // --- rota protegida sem token é rejeitada ---
        val anonymous = rest.getForEntity("/api/v1/pets", JsonNode::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)

        // --- perfil ---
        val me = rest.exchange(
            "/api/v1/tutors/me", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, me.statusCode, "me: ${me.body} / ${me.headers["WWW-Authenticate"]}")
        assertEquals("ana.smoke@example.com", me.body!!["email"].asText())

        // --- cria pet ---
        val petCreated = rest.postForEntity(
            "/api/v1/pets",
            HttpEntity(
                """{"name":"Rex","species":"Dog","breed":"SRD","birthdate":"2020-01-01"}""",
                jsonHeaders(token),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, petCreated.statusCode, "create pet: ${petCreated.body}")
        val petId = petCreated.body!!["petId"].asLong()

        // --- lista pets ---
        val pets = rest.exchange(
            "/api/v1/pets", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, pets.statusCode)
        assertEquals(1, pets.body!!["items"].size(), "pets: ${pets.body}")
        assertEquals("Rex", pets.body!!["items"][0]["name"].asText())

        // --- cria plano recorrente; ocorrências independem da conclusão anterior ---
        val startAt = LocalDateTime.now(ZoneId.systemDefault()).plusDays(1).truncatedTo(ChronoUnit.MINUTES)
        val planCreated = rest.postForEntity(
            "/api/v1/care-plans",
            HttpEntity(
                """{"petId":$petId,"type":"VACCINE","title":"Antirrábica","instructions":"Levar carteira","startAt":"$startAt","zoneId":"America/Sao_Paulo","scheduleRule":{"kind":"CALENDAR_INTERVAL","calendarUnit":"DAY","intervalCount":1,"dailyTimes":[],"repetitions":2},"reminderMinutesBefore":30}""",
                jsonHeaders(token),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, planCreated.statusCode, "create care plan: ${planCreated.body}")
        val planId = planCreated.body!!["id"].asText()

        // --- lista duas ocorrências materializadas no horizonte ---
        val occurrences = rest.exchange(
            "/api/v1/care-occurrences", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, occurrences.statusCode)
        assertEquals(2, occurrences.body!!["items"].size(), "occurrences: ${occurrences.body}")
        assertEquals("SCHEDULED", occurrences.body!!["items"][0]["status"].asText())
        val occurrenceId = occurrences.body!!["items"][0]["id"].asText()

        // --- dashboard agregado evita N+1 no frontend ---
        val dashboard = rest.exchange(
            "/api/v1/dashboard", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, dashboard.statusCode, "dashboard: ${dashboard.body}")
        assertEquals(1, dashboard.body!!["totalPets"].asInt())
        assertEquals(2, dashboard.body!!["totalEvents"].asInt())
        assertEquals("ana.smoke@example.com", dashboard.body!!["email"].asText())

        // --- conclusão idempotente e protegida contra administração em dobro ---
        val completionRequest = UUID.randomUUID()
        val complete = rest.postForEntity(
            "/api/v1/care-occurrences/$occurrenceId/complete",
            HttpEntity("""{"requestId":"$completionRequest","note":"Aplicada"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, complete.statusCode, "complete: ${complete.body}")
        assertEquals("COMPLETED", complete.body!!["status"].asText())
        val replay = rest.postForEntity(
            "/api/v1/care-occurrences/$occurrenceId/complete",
            HttpEntity("""{"requestId":"$completionRequest","note":"Aplicada"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, replay.statusCode)
        val duplicate = rest.postForEntity(
            "/api/v1/care-occurrences/$occurrenceId/complete",
            HttpEntity("""{"requestId":"${UUID.randomUUID()}"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, duplicate.statusCode)

        // --- desfazer dentro da janela segura ---
        val undo = rest.postForEntity(
            "/api/v1/care-occurrences/$occurrenceId/undo",
            HttpEntity("""{"requestId":"${UUID.randomUUID()}"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, undo.statusCode, "undo: ${undo.body}")
        assertEquals("SCHEDULED", undo.body!!["status"].asText())

        val sameRequestId = UUID.randomUUID()
        val idempotencyPool = Executors.newFixedThreadPool(2)
        try {
            val sameRequestStatuses = (1..2).map {
                CompletableFuture.supplyAsync({
                    rest.postForEntity(
                        "/api/v1/care-occurrences/$occurrenceId/complete",
                        HttpEntity("""{"requestId":"$sameRequestId"}""", jsonHeaders(token)),
                        JsonNode::class.java,
                    ).statusCode
                }, idempotencyPool)
            }.map { it.get() }
            assertTrue(sameRequestStatuses.all { it == HttpStatus.OK }, "a mesma chave deve reproduzir o sucesso: $sameRequestStatuses")
        } finally {
            idempotencyPool.shutdownNow()
        }
        val secondUndo = rest.postForEntity(
            "/api/v1/care-occurrences/$occurrenceId/undo",
            HttpEntity("""{"requestId":"${UUID.randomUUID()}"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, secondUndo.statusCode)

        val completionPool = Executors.newFixedThreadPool(2)
        try {
            val concurrentStatuses = (1..2).map {
                CompletableFuture.supplyAsync({
                    rest.postForEntity(
                        "/api/v1/care-occurrences/$occurrenceId/complete",
                        HttpEntity("""{"requestId":"${UUID.randomUUID()}"}""", jsonHeaders(token)),
                        JsonNode::class.java,
                    ).statusCode
                }, completionPool)
            }.map { it.get() }
            assertEquals(1, concurrentStatuses.count { it == HttpStatus.OK }, "apenas uma conclusão pode vencer: $concurrentStatuses")
            assertEquals(1, concurrentStatuses.count { it == HttpStatus.CONFLICT }, "a duplicada deve ser bloqueada: $concurrentStatuses")
        } finally {
            completionPool.shutdownNow()
        }

        // --- encerra plano preservando o que já foi registrado e exclui pet ---
        val endPlan = rest.exchange(
            "/api/v1/care-plans/$planId", HttpMethod.DELETE, HttpEntity<Void>(jsonHeaders(token)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, endPlan.statusCode)
        val delPet = rest.exchange(
            "/api/v1/pets/$petId", HttpMethod.DELETE, HttpEntity<Void>(jsonHeaders(token)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, delPet.statusCode)

        // --- pet já excluído responde 404 real, não 400 ---
        val petNotFound = rest.exchange(
            "/api/v1/pets/$petId", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, petNotFound.statusCode, "pet not found: ${petNotFound.body}")
        assertEquals(404, petNotFound.body!!["status"].asInt())

        val petsAfter = rest.exchange(
            "/api/v1/pets", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(token)), JsonNode::class.java,
        )
        assertEquals(0, petsAfter.body!!["items"].size(), "pets after delete: ${petsAfter.body}")
    }

    @Test
    fun `clinical timeline preserves ownership versions measurements and private attachments`() {
        val ownerSignup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Lia","lastName":null,"email":"lia.health@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, ownerSignup.statusCode)
        val ownerId = TutorId(ownerSignup.body!!["tutorId"].asLong())
        val ownerLogin = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"lia.health@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val ownerToken = ownerLogin.body!!["token"].asText()
        val petCreated = rest.postForEntity(
            "/api/v1/pets",
            HttpEntity("""{"name":"Nina","species":"Cat","breed":"SRD","birthdate":"2021-03-10"}""", jsonHeaders(ownerToken)),
            JsonNode::class.java,
        )
        val petId = petCreated.body!!["petId"].asLong()
        val occurredAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)

        val created = rest.postForEntity(
            "/api/v1/pets/$petId/health-records",
            HttpEntity(
                """{"type":"VACCINE","occurredAt":"$occurredAt","title":"Reforço V5","notes":"Sem reação imediata","productName":"V5","dosage":"1 dose","batchNumber":"LT-2026","professionalName":"Dra. Lia","clinicName":"Clínica Central","costAmount":145.90,"currency":"BRL"}""",
                jsonHeaders(ownerToken),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, created.statusCode, "create health record: ${created.body}")
        val recordId = created.body!!["id"].asText()
        val initialVersion = created.body!!["version"].asLong()
        assertEquals("VACCINE", created.body!!["type"].asText())

        val measurement = rest.postForEntity(
            "/api/v1/pets/$petId/health-measurements",
            HttpEntity(
                """{"type":"WEIGHT","value":4.75,"measuredAt":"$occurredAt","notes":"Antes do café"}""",
                jsonHeaders(ownerToken),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, measurement.statusCode, "create measurement: ${measurement.body}")
        assertEquals("KILOGRAM", measurement.body!!["unit"].asText())

        val updated = rest.exchange(
            "/api/v1/health-records/$recordId",
            HttpMethod.PUT,
            HttpEntity(
                """{"version":$initialVersion,"type":"VACCINE","occurredAt":"$occurredAt","title":"Reforço V5 felina","notes":"Sem reação","productName":"V5","dosage":"1 dose","batchNumber":"LT-2026","professionalName":"Dra. Lia","clinicName":"Clínica Central","costAmount":145.90,"currency":"BRL"}""",
                jsonHeaders(ownerToken),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, updated.statusCode, "update health record: ${updated.body}")
        val currentVersion = updated.body!!["version"].asLong()
        assertTrue(currentVersion > initialVersion)

        val stale = rest.exchange(
            "/api/v1/health-records/$recordId",
            HttpMethod.PUT,
            HttpEntity(
                """{"version":$initialVersion,"type":"VACCINE","occurredAt":"$occurredAt","title":"Edição antiga","productName":null,"dosage":null,"batchNumber":null,"professionalName":null,"clinicName":null,"costAmount":null,"currency":null}""",
                jsonHeaders(ownerToken),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, stale.statusCode)

        val otherSignup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Bia","lastName":null,"email":"bia.health@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, otherSignup.statusCode)
        val otherLogin = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"bia.health@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val otherToken = otherLogin.body!!["token"].asText()
        val crossTenantRecord = rest.exchange(
            "/api/v1/health-records/$recordId", HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders(otherToken)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantRecord.statusCode)
        val crossTenantTimeline = rest.exchange(
            "/api/v1/pets/$petId/health-records", HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders(otherToken)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantTimeline.statusCode)

        val mediaId = UUID.randomUUID()
        val ownerHouseholds = rest.exchange(
            "/api/v1/households", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(ownerToken)), JsonNode::class.java,
        ).body!!
        val ownerHouseholdId = dev.vilquer.petcarescheduler.core.domain.household.HouseholdId(
            UUID.fromString(ownerHouseholds[0]["id"].asText()),
        )
        mediaAssets.save(
            MediaAsset(
                id = mediaId,
                tutorId = ownerId,
                householdId = ownerHouseholdId,
                petId = PetId(petId),
                healthRecordId = UUID.fromString(recordId),
                purpose = MediaPurpose.HEALTH_ATTACHMENT,
                originalFilename = "resultado.pdf",
                contentType = "application/pdf",
                expectedSize = 128,
                checksumSha256 = "a".repeat(64),
                stagingKey = "staging/test/$mediaId",
                objectKey = "media/test/$mediaId.pdf",
                status = MediaStatus.READY,
                createdAt = occurredAt,
                readyAt = occurredAt,
            ),
        )
        healthAttachments.save(
            HealthRecordAttachment(
                healthRecordId = HealthRecordId(UUID.fromString(recordId)),
                mediaAssetId = mediaId,
                createdAt = occurredAt,
            ),
        )

        val anonymousPublicMedia = rest.getForEntity("/api/v1/media/$mediaId/content", JsonNode::class.java)
        assertEquals(HttpStatus.NOT_FOUND, anonymousPublicMedia.statusCode, "clinical attachment must never use public photo route")
        val anonymousDownload = rest.getForEntity("/api/v1/health-attachments/$mediaId/download-url", JsonNode::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, anonymousDownload.statusCode)
        val crossTenantDownload = rest.exchange(
            "/api/v1/health-attachments/$mediaId/download-url", HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders(otherToken)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantDownload.statusCode)

        val timeline = rest.exchange(
            "/api/v1/pets/$petId/health-records", HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders(ownerToken)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, timeline.statusCode)
        assertEquals(1, timeline.body!!["total"].asInt())
        assertEquals("resultado.pdf", timeline.body!!["items"][0]["attachments"][0]["filename"].asText())

        val delete = rest.exchange(
            "/api/v1/health-records/$recordId?version=$currentVersion", HttpMethod.DELETE,
            HttpEntity<Void>(jsonHeaders(ownerToken)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, delete.statusCode)
    }

    @Test
    fun `login rate limit blocks after repeated failed attempts`() {
        // signup próprio para não competir por janela de rate limit com o outro teste
        rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Bea","lastName":null,"email":"bea.smoke@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )

        fun wrongLogin() = HttpEntity("""{"email":"bea.smoke@example.com","password":"wrong-pwd"}""", jsonHeaders())
        // default: maxAttempts=5 na janela; a 6a tentativa deve ser bloqueada
        repeat(5) {
            val attempt = rest.postForEntity("/api/v1/auth/login", wrongLogin(), JsonNode::class.java)
            assertEquals(HttpStatus.UNAUTHORIZED, attempt.statusCode, "attempt $it: ${attempt.body}")
        }
        val blocked = rest.postForEntity("/api/v1/auth/login", wrongLogin(), JsonNode::class.java)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.statusCode, "blocked: ${blocked.body}")
    }

    @Test
    fun `concurrent login attempts do not return conflict`() {
        rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Dan","lastName":null,"email":"dan.smoke@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )

        val loginBody = HttpEntity("""{"email":"dan.smoke@example.com","password":"wrong-pwd"}""", jsonHeaders())
        val pool = Executors.newFixedThreadPool(10)
        try {
            val statuses = (1..10).map {
                CompletableFuture.supplyAsync({
                    rest.postForEntity("/api/v1/auth/login", loginBody, JsonNode::class.java).statusCode
                }, pool)
            }.map { it.get() }

            assertTrue(
                statuses.none { it == HttpStatus.CONFLICT },
                "login concorrente não deve retornar 409: $statuses",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `password reset request runs its atomic transaction without a real SpringTransactionPort wiring gap`() {
        rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Carla","lastName":null,"email":"carla.smoke@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )

        // O token vai por e-mail (fora do escopo deste teste); o que importa
        // aqui é que a transação real (invalidar + criar token) não estoure
        // por falta do bean de PlatformTransactionManager/TransactionTemplate.
        val forgot = rest.postForEntity(
            "/api/v1/auth/password/forgot",
            HttpEntity("""{"email":"carla.smoke@example.com"}""", jsonHeaders()),
            Void::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, forgot.statusCode)

        val invalidToken = rest.exchange(
            "/api/v1/auth/password/reset/validate?token=not-a-real-token",
            HttpMethod.GET, HttpEntity<Void>(jsonHeaders()), Void::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, invalidToken.statusCode)
    }

    @Test
    fun `reminder outbox enqueues against the real database and the relay retries on delivery failure`() {
        val signup = rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Duda","lastName":null,"email":"duda.smoke@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.CREATED, signup.statusCode)
        val tutorId = TutorId(signup.body!!["tutorId"].asLong())

        val login = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"duda.smoke@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val token = login.body!!["token"].asText()

        val petCreated = rest.postForEntity(
            "/api/v1/pets",
            HttpEntity("""{"name":"Toby","species":"Dog","breed":null,"birthdate":"2020-01-01"}""", jsonHeaders(token)),
            JsonNode::class.java,
        )
        val petId = petCreated.body!!["petId"].asLong()

        // dateStart "hoje", no fim do dia, no fuso da aplicação: precisa estar
        // dentro da janela que o scheduler de detecção varre e ainda satisfazer
        // @FutureOrPresent no DTO (por isso não usamos meio-dia, que já teria
        // passado se o teste rodar à tarde).
        val todayLate = java.time.LocalDate.now(ZoneId.of("America/Sao_Paulo")).atTime(23, 59)
        val eventCreated = registerLegacyEvent.execute(
            RegisterEventCommand(PetId(petId), EventType.VACCINE, "lembrete real", todayLate),
            tutorId,
        )
        assertTrue(eventCreated.eventId.value > 0)

        // Chama os beans diretamente (em vez de esperar o cron) para exercitar
        // o ReminderOutboxJpaAdapter de verdade, contra PostgreSQL, sem fakes.
        sendDailyReminders.sendRemindersForToday()
        sendDailyReminders.sendRemindersForToday() // idempotência: não deve duplicar a mensagem

        val enqueued = reminderOutboxRepository.findAll().filter { it.tutorEmail == "duda.smoke@example.com" }
        assertEquals(1, enqueued.size, "a segunda varredura não deve ter criado uma segunda mensagem")
        assertEquals(0, enqueued.first().attempts)

        // A API de e-mail não está configurada neste ambiente de teste, então
        // a entrega falha de verdade — o que comprova que o relay trata falha
        // como "tentar de novo", não como sucesso silencioso.
        dispatchPendingReminders.dispatchPendingReminders()

        val afterDispatch = reminderOutboxRepository.findById(enqueued.first().id!!).orElseThrow()
        assertEquals(1, afterDispatch.attempts, "a tentativa de entrega deve ter sido contabilizada")
        assertTrue(afterDispatch.sentAt == null, "a API de e-mail indisponível não deve ter sido marcada como enviada")
    }

    @Test
    fun `refresh token rotation, reuse detection and logout`() {
        rest.postForEntity(
            "/api/v1/public/signup",
            HttpEntity(
                """{"firstName":"Fefe","lastName":null,"email":"fefe.smoke@example.com","rawPassword":"correct-pwd"}""",
                jsonHeaders(),
            ),
            JsonNode::class.java,
        )

        val login = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"fefe.smoke@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode)
        val firstRefreshCookie = refreshCookieValue(login)

        // --- refresh rotaciona: novo access + novo cookie ---
        val refreshed = rest.postForEntity(
            "/api/v1/auth/refresh", HttpEntity<Void>(cookieHeaders(firstRefreshCookie)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, refreshed.statusCode, "refresh: ${refreshed.body}")
        val newAccessToken = refreshed.body!!["token"].asText()
        assertTrue(newAccessToken.isNotBlank())
        val secondRefreshCookie = refreshCookieValue(refreshed)
        assertTrue(secondRefreshCookie != firstRefreshCookie, "refresh deve rotacionar o cookie")

        // --- o novo access token funciona numa rota protegida ---
        val me = rest.exchange(
            "/api/v1/tutors/me", HttpMethod.GET, HttpEntity<Void>(jsonHeaders(newAccessToken)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.OK, me.statusCode, "me: ${me.body}")

        // --- reapresentar o cookie antigo (já rotacionado) é reuso: revoga a família inteira ---
        val reuse = rest.postForEntity(
            "/api/v1/auth/refresh", HttpEntity<Void>(cookieHeaders(firstRefreshCookie)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, reuse.statusCode, "reuse: ${reuse.body}")

        // --- e a família inteira morreu: o cookie rotacionado (válido) também não serve mais ---
        val deadFamily = rest.postForEntity(
            "/api/v1/auth/refresh", HttpEntity<Void>(cookieHeaders(secondRefreshCookie)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, deadFamily.statusCode, "dead family: ${deadFamily.body}")

        // --- logout revoga o cookie atual ---
        val login2 = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"fefe.smoke@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val cookieToLogout = refreshCookieValue(login2)
        val logout = rest.postForEntity(
            "/api/v1/auth/logout", HttpEntity<Void>(cookieHeaders(cookieToLogout)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, logout.statusCode)
        val refreshAfterLogout = rest.postForEntity(
            "/api/v1/auth/refresh", HttpEntity<Void>(cookieHeaders(cookieToLogout)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, refreshAfterLogout.statusCode)

        // --- logout-all exige autenticação por access token ---
        val logoutAllAnonymous = rest.postForEntity("/api/v1/auth/logout-all", HttpEntity<Void>(jsonHeaders()), Void::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, logoutAllAnonymous.statusCode)

        val login3 = rest.postForEntity(
            "/api/v1/auth/login",
            HttpEntity("""{"email":"fefe.smoke@example.com","password":"correct-pwd"}""", jsonHeaders()),
            JsonNode::class.java,
        )
        val accessToken3 = login3.body!!["token"].asText()
        val cookie3 = refreshCookieValue(login3)
        val logoutAll = rest.postForEntity(
            "/api/v1/auth/logout-all", HttpEntity<Void>(jsonHeaders(accessToken3)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, logoutAll.statusCode)
        val refreshAfterLogoutAll = rest.postForEntity(
            "/api/v1/auth/refresh", HttpEntity<Void>(cookieHeaders(cookie3)), JsonNode::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, refreshAfterLogoutAll.statusCode)
    }
}
