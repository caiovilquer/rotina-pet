rootProject.name = "petcarescheduler"
include("core", "application", "adapter-ai", "adapter-persistence", "adapter-messaging", "adapter-rest", "adapter-storage", "bootstrap")
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
plugins {
    // O catalog (gradle/libs.versions.toml) ainda não está disponível neste
    // ponto do settings.gradle.kts (bootstrap do próprio catalog depende do
    // pluginManagement já estar resolvido) — única exceção do projeto a usar
    // uma versão literal em vez de libs.versions.toml.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
