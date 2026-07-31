plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("io.micrometer:micrometer-core")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation(libs.openai.java)

    testImplementation(libs.junit.jupiter.api)
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform { excludeTags("openai-live") }
}

tasks.register<Test>("openAiEval") {
    group = "verification"
    description = "Executa avaliações sintéticas reais contra a OpenAI; nunca faz parte de check/CI comum."
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("openai-live") }
    shouldRunAfter(tasks.test)
    doFirst {
        require(!System.getenv("OPENAI_API_KEY").isNullOrBlank()) {
            "OPENAI_API_KEY é obrigatória para openAiEval"
        }
    }
}
