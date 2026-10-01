import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "21" }
}

tasks.withType<BootJar>().configureEach {
    archiveFileName.set("rotinapet.jar")
}

springBoot {
    mainClass.set("dev.vilquer.petcarescheduler.PetCareSchedulerApplicationKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":application"))
    implementation(project(":adapter-ai"))
    implementation(project(":adapter-rest"))
    implementation(project(":adapter-persistence"))
    implementation(project(":adapter-messaging"))
    implementation(project(":adapter-storage"))

    implementation("org.springframework.boot:spring-boot-starter")
    // SmokeE2ETest fala HTTP diretamente (TestRestTemplate/HttpEntity); os
    // controllers de fato vêm do adapter-rest, mas o compile classpath do
    // próprio bootstrap precisa dos tipos de spring-web.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // SmokeE2ETest inspeciona ReminderOutboxJpaRepository diretamente para
    // verificar o outbox contra o banco real, não só com fakes.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Observabilidade mínima: /actuator/health, /info, /metrics (Micrometer
    // vem transitivamente com o starter).
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Garante que os schedulers rodem em uma única instância quando houver
    // mais de uma réplica do bootstrap no ar (ver AUDITORIA.md).
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
    // TestRestTemplate cai para o HttpURLConnection da JDK sem um client HTTP
    // dedicado no classpath; esse client lança HttpRetryException em qualquer
    // resposta 401 recebida com corpo em modo streaming (bug/limitação da JDK,
    // não do código de produção — confirmado batendo direto com curl).
    testImplementation(libs.httpclient5)
    // Gradle empacota seu próprio junit-platform-launcher, que fica desalinhado
    // com o junit-platform-engine mais novo trazido pelo BOM do Spring Boot;
    // declarar a versão do BOM explicitamente resolve o conflito.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testes de arquitetura (fronteira hexagonal por módulo)
    testImplementation(libs.konsist)
}

tasks.test {
    useJUnitPlatform()
}
