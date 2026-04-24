rootProject.name = "pubsub-kafka-migrator"

include("platform-common")
include("platform-project")
include("platform-job")
include("platform-orchestrator")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
