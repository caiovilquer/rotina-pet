package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionPetContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OpenAiAdaptersHttpTest {
    private val mapper = jacksonObjectMapper()
    private val context = AiInvocationContext(
        HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000041")),
        TutorId(77),
    )

    @Test
    fun `care draft uses bearer structured outputs store false model reasoning and opaque safety id`() = MockOpenAi().use { server ->
        server.respond(200, responseJson(careOutput()))
        val adapter = careAdapter(server)

        val result = adapter.extract(careRequest())

        assertEquals(PetId(1), result.fields.petId)
        assertEquals("Medicamento", result.fields.title)
        val request = server.requests.single()
        assertEquals("Bearer test-key", request.authorization)
        assertEquals("/v1/responses", request.path)
        val body = mapper.readTree(request.body)
        assertEquals("gpt-5.6-luna", body["model"].asText())
        assertFalse(body["store"].asBoolean())
        assertEquals("low", body["reasoning"]["effort"].asText())
        assertEquals("json_schema", body["text"]["format"]["type"].asText())
        assertTrue(body["text"]["format"]["strict"].asBoolean())
        assertTrue(body["safety_identifier"].asText().startsWith("rp1_"))
        assertFalse(request.body.contains(context.householdId.value.toString()))
        assertFalse(request.body.contains(context.actorTutorId!!.value.toString()))
        assertFalse(request.body.contains("\"petId\""))
    }

    @Test
    fun `client retries 429 and 5xx only up to configured maximum`() = MockOpenAi().use { server ->
        server.respond(429, errorJson("rate_limit_exceeded"))
        server.respond(500, errorJson("server_error"))
        server.respond(200, responseJson(careOutput()))

        careAdapter(server, retries = 2).extract(careRequest())

        assertEquals(3, server.requests.size)
    }

    @Test
    fun `rate limit after retries becomes stable public error`() = MockOpenAi().use { server ->
        repeat(3) { server.respond(429, errorJson("rate_limit_exceeded")) }

        val error = assertThrows(CareInstructionExtractionException::class.java) {
            careAdapter(server, retries = 2).extract(careRequest())
        }

        assertEquals("AI_RATE_LIMITED", error.code)
        assertTrue(error.retryable)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `refusal and incomplete responses are rejected without leaking provider text`() {
        MockOpenAi().use { server ->
            server.respond(200, responseJson(null, refusal = "sensitive refusal"))
            val error = assertThrows(CareInstructionExtractionException::class.java) {
                careAdapter(server).extract(careRequest())
            }
            assertEquals("AI_OUTPUT_INVALID", error.code)
            assertFalse(error.message.orEmpty().contains("sensitive"))
        }
        MockOpenAi().use { server ->
            server.respond(200, responseJson(null, status = "incomplete"))
            val error = assertThrows(CareInstructionExtractionException::class.java) {
                careAdapter(server).extract(careRequest())
            }
            assertEquals("AI_OUTPUT_INVALID", error.code)
        }
    }

    @Test
    fun `timeout becomes provider unavailable and honors configured duration`() = MockOpenAi().use { server ->
        server.respondDelayed(250, 200, responseJson(careOutput()))

        val error = assertThrows(CareInstructionExtractionException::class.java) {
            careAdapter(server, timeout = Duration.ofMillis(50), retries = 0).extract(careRequest())
        }

        assertEquals("AI_PROVIDER_UNAVAILABLE", error.code)
        assertTrue(error.retryable)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `embedding sends explicit dimensions batches and hmac user`() = MockOpenAi().use { server ->
        server.respond(200, embeddingJson(2))
        val settings = settings(server, batch = 2)
        val adapter = OpenAiEmbeddingAdapter(client(settings), settings, SimpleMeterRegistry())

        val vectors = adapter.embed(listOf("primeiro", "segundo"), context)

        assertEquals(2, vectors.size)
        assertTrue(vectors.all { it.size == 512 })
        assertEquals("openai/text-embedding-3-small/512", adapter.signature)
        val body = mapper.readTree(server.requests.single().body)
        assertEquals(512, body["dimensions"].asInt())
        assertEquals("text-embedding-3-small", body["model"].asText())
        assertTrue(body["user"].asText().startsWith("rp1_"))
        assertNotEquals(context.actorTutorId!!.value.toString(), body["user"].asText())
    }

    @Test
    fun `embedding invalid dimension is rejected`() = MockOpenAi().use { server ->
        server.respond(200, embeddingJson(1, dimensions = 3))
        val settings = settings(server)
        val adapter = OpenAiEmbeddingAdapter(client(settings), settings, SimpleMeterRegistry())

        val error = assertThrows(EmbeddingException::class.java) { adapter.embed(listOf("texto"), context) }

        assertEquals("AI_OUTPUT_INVALID", error.code)
    }

    @Test
    fun `grounded answer maps opaque chunk references back to authorized ids`() = MockOpenAi().use { server ->
        val output = mapper.writeValueAsString(
            mapOf("answer" to "O exame foi registrado.", "citedChunkReferences" to listOf("chunk-1"), "insufficientEvidence" to false),
        )
        server.respond(200, responseJson(output))
        val settings = settings(server)
        val adapter = OpenAiGroundedAnswerGeneratorAdapter(client(settings), settings, SimpleMeterRegistry(), mapper)
        val chunkId = UUID.randomUUID()
        val evidence = GroundingEvidence(
            chunkId, UUID.randomUUID(), KnowledgeSourceType.HEALTH_ATTACHMENT, UUID.randomUUID(), "Exame", 1, "Exame registrado.",
        )

        val result = adapter.answer("O que consta no exame?", listOf(evidence), context)

        assertEquals(setOf(chunkId), result.citedChunkIds)
        val request = server.requests.single()
        assertFalse(request.body.contains(chunkId.toString()))
        assertTrue(request.body.contains("chunk-1"))
    }

    private fun careAdapter(server: MockOpenAi, timeout: Duration = Duration.ofSeconds(2), retries: Int = 0): OpenAiCareInstructionExtractorAdapter {
        val settings = settings(server, timeout = timeout, retries = retries)
        return OpenAiCareInstructionExtractorAdapter(client(settings), settings, SimpleMeterRegistry(), mapper)
    }

    private fun client(settings: AiAdapterSettings) = OpenAIOkHttpClient.builder()
        .apiKey(settings.apiKey)
        .baseUrl(settings.baseUrl)
        .timeout(settings.timeout)
        .maxRetries(settings.maxRetries)
        .build()

    private fun settings(
        server: MockOpenAi,
        timeout: Duration = Duration.ofSeconds(2),
        retries: Int = 0,
        batch: Int = 64,
    ) = AiAdapterSettings(
        provider = "openai",
        aiEnabled = true,
        ragEnabled = true,
        apiKey = "test-key",
        baseUrl = server.baseUrl,
        careDraftModel = "gpt-5.6-luna",
        groundedAnswerModel = "gpt-5.6-luna",
        embeddingModel = "text-embedding-3-small",
        embeddingDimensions = 512,
        embeddingBatchSize = batch,
        reasoningEffort = "low",
        timeout = timeout,
        maxRetries = retries,
        safetyIdentifierKey = "staging-test-key-with-more-than-32-characters",
    )

    private fun careRequest() = CareInstructionExtractionRequest(
        instruction = "Medicamento da Luna amanhã às 08:00",
        receivedAt = Instant.parse("2026-07-14T12:00:00Z"),
        zoneId = ZoneId.of("America/Sao_Paulo"),
        pets = listOf(CareInstructionPetContext(PetId(1), "Luna")),
        context = context,
    )

    private fun careOutput() = mapper.writeValueAsString(
        mapOf(
            "petReference" to "pet-1",
            "type" to "MEDICINE",
            "title" to "Medicamento",
            "instructions" to "Medicamento da Luna",
            "startAt" to "2026-07-15T11:00:00Z",
            "timezone" to "America/Sao_Paulo",
            "schedule" to mapOf(
                "kind" to "ONE_TIME", "calendarUnit" to null, "intervalCount" to null,
                "fixedIntervalMinutes" to null, "dailyTimes" to emptyList<String>(),
                "repetitions" to null, "endAt" to null,
            ),
            "evidence" to listOf(
                mapOf("field" to "PET", "evidence" to "Luna", "provenance" to "EXPLICIT"),
                mapOf("field" to "TYPE", "evidence" to "Medicamento", "provenance" to "EXPLICIT"),
            ),
            "missingFields" to emptyList<String>(),
            "warnings" to emptyList<Map<String, Any>>(),
        ),
    )

    private fun responseJson(output: String?, refusal: String? = null, status: String = "completed"): String {
        val content = when {
            refusal != null -> listOf(mapOf("type" to "refusal", "refusal" to refusal))
            output != null -> listOf(mapOf("type" to "output_text", "text" to output, "annotations" to emptyList<Any>(), "logprobs" to emptyList<Any>()))
            else -> emptyList()
        }
        return mapper.writeValueAsString(
            mapOf(
                "id" to "resp_test", "object" to "response", "created_at" to 1_752_499_200,
                "status" to status, "error" to null,
                "incomplete_details" to if (status == "incomplete") mapOf("reason" to "max_output_tokens") else null,
                "model" to "gpt-5.6-luna", "output" to listOf(
                    mapOf("id" to "msg_test", "type" to "message", "role" to "assistant", "status" to status, "content" to content),
                ),
                "parallel_tool_calls" to false, "tool_choice" to "auto", "tools" to emptyList<Any>(),
                "usage" to mapOf(
                    "input_tokens" to 12, "input_tokens_details" to mapOf("cached_tokens" to 0),
                    "output_tokens" to 8, "output_tokens_details" to mapOf("reasoning_tokens" to 0), "total_tokens" to 20,
                ),
            ),
        )
    }

    private fun embeddingJson(count: Int, dimensions: Int = 512): String = mapper.writeValueAsString(
        mapOf(
            "object" to "list",
            "data" to (0 until count).map { index ->
                mapOf("object" to "embedding", "embedding" to List(dimensions) { if (it == index) 1.0 else 0.0 }, "index" to index)
            },
            "model" to "text-embedding-3-small",
            "usage" to mapOf("prompt_tokens" to count, "total_tokens" to count),
        ),
    )

    private fun errorJson(code: String) = mapper.writeValueAsString(
        mapOf("error" to mapOf("message" to "simulated", "type" to code, "param" to null, "code" to code)),
    )

    private data class CapturedRequest(val path: String, val authorization: String?, val body: String)

    private class MockOpenAi : AutoCloseable {
        private data class Reply(val status: Int, val body: String, val delayMillis: Long = 0)
        private val replies = CopyOnWriteArrayList<Reply>()
        private val index = AtomicInteger()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            this.executor = this@MockOpenAi.executor
            start()
        }
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val baseUrl: String = "http://127.0.0.1:${server.address.port}/v1"

        fun respond(status: Int, body: String) { replies += Reply(status, body) }
        fun respondDelayed(delayMillis: Long, status: Int, body: String) { replies += Reply(status, body, delayMillis) }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            requests += CapturedRequest(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
            val reply = replies.getOrElse(index.getAndIncrement()) { replies.last() }
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
