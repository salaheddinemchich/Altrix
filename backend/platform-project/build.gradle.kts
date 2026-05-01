plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // Shared domain kernel
    implementation(project(":platform-common"))

    // Web layer
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Messaging — fires job-created events to Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Object storage — stores uploaded ZIPs in MinIO
    implementation("io.minio:minio:8.5.12")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
