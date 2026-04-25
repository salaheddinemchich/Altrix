plugins {
    id("org.springframework.boot")      version "3.3.4"    apply false
    id("io.spring.dependency-management") version "1.1.6"  apply false
    id("org.sonarqube")                 version "5.1.0.4882"
    java
}

// ── SonarQube root config ─────────────────────────────────────────────────────
sonar {
    properties {
        property("sonar.projectKey",  "pubsub-kafka-migrator")
        property("sonar.projectName", "PubSub to Kafka Migrator")
        property("sonar.java.source", "21")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions",
            "**/build/**,**/.gradle/**,**/infra/postgres/**,**/*Application.java,**/generated/**")

        // Aggregate coverage from all submodules
        property("sonar.coverage.jacoco.xmlReportPaths",
            subprojects.map {
                "${it.projectDir}/build/reports/jacoco/test/jacocoTestReport.xml"
            }.joinToString(","))
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    group   = "com.migrator"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
        }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        testCompileOnly("org.projectlombok:lombok")
        testAnnotationProcessor("org.projectlombok:lombok")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Always generate coverage report after tests
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)   // needed by SonarQube
            html.required.set(true)  // human-readable local report
            csv.required.set(false)
        }
    }

    // Only modules with SpringBoot plugin get a bootJar
    plugins.withType<org.springframework.boot.gradle.plugin.SpringBootPlugin> {
        tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
            launchScript()
        }
    }
}

// ── Aggregate task: run all subproject tests + coverage in one command ────────
tasks.register("testAll") {
    group = "verification"
    description = "Run tests and generate JaCoCo reports for all subprojects"
    dependsOn(subprojects.map { "${it.path}:test" })
    dependsOn(subprojects.map { "${it.path}:jacocoTestReport" })
}
