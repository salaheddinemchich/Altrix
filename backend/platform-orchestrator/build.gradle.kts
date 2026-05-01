plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

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

    // HTTP client — calls Groq AI API
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
