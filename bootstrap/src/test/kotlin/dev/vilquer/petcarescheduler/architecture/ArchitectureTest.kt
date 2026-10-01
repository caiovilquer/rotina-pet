package dev.vilquer.petcarescheduler.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testes de arquitetura da Fase 1: garantem por escopo de módulo Gradle (não
 * por convenção de nome) que a fronteira hexagonal é um contrato, não um
 * acordo de cavalheiros. Cada teste falha com a lista de violações.
 */
class ArchitectureTest {

    private val frameworkPrefixes = listOf(
        "org.springframework",
        "jakarta.",
        "org.hibernate",
        "com.fasterxml.jackson",
        "io.jsonwebtoken",
    )

    private fun importNames(moduleName: String): List<String> =
        Konsist.scopeFromModule(moduleName)
            .files
            .flatMap { it.imports }
            .map { it.name }

    @Test
    fun `core nao importa framework nem outras camadas`() {
        val forbidden = frameworkPrefixes + listOf(
            "dev.vilquer.petcarescheduler.usecase",
            "dev.vilquer.petcarescheduler.application",
            "dev.vilquer.petcarescheduler.infra",
            "dev.vilquer.petcarescheduler.bootstrap",
        )
        val violations = importNames("core").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "core importou tipos de framework/camadas externas: $violations")
    }

    @Test
    fun `application nao importa framework`() {
        val violations = importNames("application").filter { imp -> frameworkPrefixes.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "application importou tipos de framework: $violations")
    }

    @Test
    fun `application nao importa nenhum adapter nem o bootstrap`() {
        val forbidden = listOf(
            "dev.vilquer.petcarescheduler.application.adapter.input.rest",
            "dev.vilquer.petcarescheduler.application.adapter.input.security",
            "dev.vilquer.petcarescheduler.application.adapter.input.scheduler",
            "dev.vilquer.petcarescheduler.application.config",
            "dev.vilquer.petcarescheduler.application.security",
            "dev.vilquer.petcarescheduler.infra.",
        )
        val violations = importNames("application").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "application importou código de adapter/bootstrap: $violations")
    }

    // Pacotes exclusivos de cada adapter, usados para flagrar um adapter
    // importando código de outro adapter (nomes de pacote preservados da
    // reorganização de módulos da Fase 1; ver PLANO-FASE-1.md, seção 2, D-nota).
    private val restOnlyPackages = listOf(
        "dev.vilquer.petcarescheduler.application.adapter.input.rest",
        "dev.vilquer.petcarescheduler.application.adapter.input.security",
        "dev.vilquer.petcarescheduler.application.config",
        "dev.vilquer.petcarescheduler.application.security",
        "dev.vilquer.petcarescheduler.application.mapper.EventDtoMapper",
        "dev.vilquer.petcarescheduler.application.mapper.PetDtoMapper",
        "dev.vilquer.petcarescheduler.application.mapper.TutorDtoMapper",
        "dev.vilquer.petcarescheduler.application.mapper.LocationDtoMapper",
    )
    private val persistenceOnlyPackages = listOf(
        "dev.vilquer.petcarescheduler.infra.adapter.output.persistence",
        "dev.vilquer.petcarescheduler.infra.adapter.output.external",
        "dev.vilquer.petcarescheduler.infra.adapter.output.reset",
    )
    private val messagingOnlyPackages = listOf(
        "dev.vilquer.petcarescheduler.infra.adapter.output.mail",
        "dev.vilquer.petcarescheduler.infra.adapter.output.notification",
        "dev.vilquer.petcarescheduler.infra.adapter.output.places",
        "dev.vilquer.petcarescheduler.infra.adapter.output.cache",
        "dev.vilquer.petcarescheduler.infra.config",
    )
    private val aiOnlyPackages = listOf(
        "dev.vilquer.petcarescheduler.infra.adapter.output.ai",
    )

    @Test
    fun `adapter-rest nao importa outro adapter`() {
        val forbidden = persistenceOnlyPackages + messagingOnlyPackages + aiOnlyPackages
        val violations = importNames("adapter-rest").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "adapter-rest importou código de outro adapter: $violations")
    }

    @Test
    fun `adapter-persistence nao importa outro adapter`() {
        val forbidden = restOnlyPackages + messagingOnlyPackages + aiOnlyPackages
        val violations = importNames("adapter-persistence").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "adapter-persistence importou código de outro adapter: $violations")
    }

    @Test
    fun `adapter-messaging nao importa outro adapter`() {
        val forbidden = restOnlyPackages + persistenceOnlyPackages + aiOnlyPackages
        val violations = importNames("adapter-messaging").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "adapter-messaging importou código de outro adapter: $violations")
    }

    @Test
    fun `adapter-ai nao importa outro adapter`() {
        val forbidden = restOnlyPackages + persistenceOnlyPackages + messagingOnlyPackages
        val violations = importNames("adapter-ai").filter { imp -> forbidden.any { imp.startsWith(it) } }
        assertTrue(violations.isEmpty(), "adapter-ai importou código de outro adapter: $violations")
    }

    @Test
    fun `driving ports terminam em UseCase`() {
        val offenders = Konsist.scopeFromModule("application")
            .interfaces()
            .filter { it.resideInPackage("..contract.drivingports..") }
            .filterNot { it.name.endsWith("UseCase") }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "driving ports fora da convenção de nome: $offenders")
    }

    @Test
    fun `driven ports terminam em Port`() {
        val offenders = Konsist.scopeFromModule("application")
            .interfaces()
            .filter { it.resideInPackage("..contract.drivenports..") }
            .filterNot { it.name.endsWith("Port") }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "driven ports fora da convenção de nome: $offenders")
    }
}
