plugins {
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("org.sonarqube") version "5.1.0.4882" apply false
    java
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

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
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    plugins.withType<org.springframework.boot.gradle.plugin.SpringBootPlugin> {
        tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
            launchScript()
        }
    }
}
