rootProject.name = "altrix"

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
