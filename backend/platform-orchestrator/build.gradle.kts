plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val langchain4jVersion = "0.36.2"
val resilience4jVersion = "2.2.0"
val langgraph4jVersion = "1.5.12"
val openRewriteVersion = "8.41.1"

dependencies {
    implementation(project(":platform-common"))

    // Web + WebSocket
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Email — approval notifications (#66)
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Security — GitHub OAuth2 login + JWT RS256 + refresh tokens + rate limiting
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    testImplementation("org.springframework.security:spring-security-test")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Persistence — provider config overrides + RAG embeddings stored in PostgreSQL + pgvector
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kafka — consumes migration.job.created, publishes status updates
    implementation("org.springframework.kafka:spring-kafka")

    // MinIO — reads uploaded ZIPs, writes migrated ZIPs
    implementation("io.minio:minio:8.5.12")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // LangChain4j — multi-provider AI routing + RAG (embedding + vector store)
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-open-ai:$langchain4jVersion")   // covers Groq (OpenAI-compat) and OpenAI
    implementation("dev.langchain4j:langchain4j-anthropic:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-ollama:$langchain4jVersion")
    // EmbeddingModel is in the core langchain4j artifact — no separate dep needed.
    // pgvector store is implemented via raw JdbcTemplate (PgVectorEmbeddingStoreAdapter)
    // so no langchain4j-pgvector dep required (avoids langchain4j 1.x API conflict).

    // LangGraph4j — stateful agent workflow graph with conditional routing + checkpointing
    implementation("org.bsc.langgraph4j:langgraph4j-core:$langgraph4jVersion")
    implementation("org.bsc.langgraph4j:langgraph4j-langchain4j:$langgraph4jVersion")

    // Redis — job status cache + LangGraph checkpoint persistence (commons-pool2 for Lettuce pooling)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.apache.commons:commons-pool2")

    // JTokkit — token counting for AI prompt budget enforcement (#48)
    implementation("com.knuddels:jtokkit:1.1.0")

    // Resilience4j — circuit breaker + retry + bulkhead per provider
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-bulkhead:$resilience4jVersion")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Docker SDK — used by DockerSandboxRunner (#16/#17/#99-#103) to run
    // `mvn compile` against migrated source in an isolated container.
    // zerodep transport keeps the dependency footprint small (no httpclient5
    // / netty pull-through).
    implementation("com.github.docker-java:docker-java-core:3.4.0")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.0")

    // JavaParser — AST-based intra-project contract validation (interface
    // overrides, method-call resolution, constructor arity).  Runs against
    // the migrated artifact BEFORE sandbox compile so violations can be
    // patched via a minimal-LLM-edit loop instead of failing the Docker
    // build.  Symbol-solver flavour pulls in the same library; the plain
    // `javaparser-core` is enough for our local intra-project resolution.
    implementation("com.github.javaparser:javaparser-core:3.26.2")

    // OpenRewrite — Lossless Semantic Tree (type-attributed) for the
    // ProjectMapper phase.  Parses the project's Java sources together to
    // build a project-wide SemanticGraph (classes/methods/calls/inheritance/
    // imports) the migrator reads so it stops migrating files blind.
    // rewrite-java is the language module; rewrite-java-21 supplies the
    // Java-21 parser implementation selected at runtime.
    implementation(platform("org.openrewrite:rewrite-bom:$openRewriteVersion"))
    implementation("org.openrewrite:rewrite-java")
    runtimeOnly("org.openrewrite:rewrite-java-21")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// Forward any `altrix.*` system properties from the Gradle CLI into the test
// JVM.  Lets opt-in smoke tests (e.g. DockerSandboxRunnerOriginalProjectSmokeTest)
// be activated via `-Daltrix.sandbox.smoke.root=...` without modifying source.
tasks.withType<Test> {
    System.getProperties()
        .filterKeys { (it as? String)?.startsWith("altrix.") == true }
        .forEach { (k, v) -> systemProperty(k as String, v as Any) }
}

// Load .env from the backend root into bootRun environment automatically.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}
