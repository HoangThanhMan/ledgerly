pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads JDK 25 automatically if it is missing (Gradle toolchains)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ledgerly"

include(
    "ledger-contracts",
    "ledger-app",
    "mock-bank",
    "notification-consumer",
)
