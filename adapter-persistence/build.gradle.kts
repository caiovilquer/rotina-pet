plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.dependency.management)
    `java-library`
}

kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "21" }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":application"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // Spring Data precisa de kotlin-reflect para introspectar repositórios Kotlin
    implementation(kotlin("reflect"))

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    // Gradle empacota seu próprio junit-platform-launcher, que fica desalinhado
    // com o junit-platform-engine mais novo trazido pelo BOM do Spring Boot;
    // declarar a versão do BOM explicitamente resolve o conflito.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // As mesmas migrações Flyway (common + postgresql) usadas em produção
    // são exercitadas contra PostgreSQL/pgvector real nos testes.
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

tasks.test {
    useJUnitPlatform()
}
