plugins {
    id("org.springframework.boot")        version "3.3.4"   apply false
    id("io.spring.dependency-management") version "1.1.6"   apply false
    id("org.sonarqube")                   version "5.1.0.4882"
    java
}

// sonar-project.properties handles all project config.
// Only host/token are set here (injected at runtime via -D flags or env).
sonar {
    properties {
        property("sonar.projectKey",  "altrix")
        property("sonar.projectName", "Altrix")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    group   = "com.altrix"
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
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        // Adapter and infrastructure layers are tested via integration tests (Testcontainers).
        // Exclude them from unit-test coverage so the domain-layer % is meaningful.
        classDirectories.setFrom(
            files(classDirectories.files.map { dir ->
                fileTree(dir) {
                    exclude(
                        "**/adapter/**",
                        "**/infrastructure/**",
                        "**/*Application.class"
                    )
                }
            })
        )
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    plugins.withType<org.springframework.boot.gradle.plugin.SpringBootPlugin> {
        tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
            launchScript()
        }
    }
}
