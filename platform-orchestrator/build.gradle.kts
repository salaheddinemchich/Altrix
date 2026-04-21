plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":platform-common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // MinIO SDK for agent storage
    implementation("io.minio:minio:8.5.12")

    // HTTP Client for AI adapter calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
