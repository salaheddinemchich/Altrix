plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val langchain4jVersion = "0.36.2"
val resilience4jVersion = "2.2.0"

dependencies {
    implementation(project(":platform-common"))

    // Web + WebSocket
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kafka — consumes migration.job.created, publishes status updates
    implementation("org.springframework.kafka:spring-kafka")

    // MinIO — reads uploaded ZIPs, writes migrated ZIPs
    implementation("io.minio:minio:8.5.12")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // LangChain4j — multi-provider AI routing
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-open-ai:$langchain4jVersion")   // covers Groq (OpenAI-compat) and OpenAI
    implementation("dev.langchain4j:langchain4j-anthropic:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-ollama:$langchain4jVersion")

    // Resilience4j — circuit breaker + retry per provider
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
}
