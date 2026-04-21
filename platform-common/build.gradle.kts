plugins {
    java
}

/*
 * platform-common is a plain Java library — no Spring Boot application,
 * no embedded server, no bootJar. It is imported by every service module
 * as a dependency via project(":platform-common").
 *
 * Rules for this module:
 *   - No @SpringBootApplication
 *   - No @RestController, @Service, @Repository Spring annotations
 *   - No JPA / Hibernate imports
 *   - No Kafka imports
 *   - Pure Java domain objects, enums, exceptions only
 */

dependencies {
    // Validation annotations (@NotNull, @NotBlank, etc.) used on Value Objects
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")

    // Jackson for JSON serialisation of shared DTOs
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Lombok — version managed by root build.gradle.kts BOM
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
