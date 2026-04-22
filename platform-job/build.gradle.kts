plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":platform-common"))

    // Web
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Persistence + Criteria API
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Redis — job status cache
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Kafka — consume project.registered, publish migration.job.created
    implementation("org.springframework.kafka:spring-kafka")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
