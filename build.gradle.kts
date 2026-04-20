plugins {
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    java
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.migrator"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        compileOnly("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
    }

    // This block ensures that only modules with the Boot plugin try to configure bootJar
    plugins.withType<org.springframework.boot.gradle.plugin.SpringBootPlugin> {
        tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
            launchScript()
        }
    }
}
