plugins {
    id("ledgerly.spring-boot-app")
}

description = "Main application: e-wallet on a double-entry ledger (modular monolith)"

// -Pledgerly.test.seed=<n> replays the transfers of a failed concurrency run. The next two resize the runs.
// -Pledgerly.openapi.update=true rewrites openapi.yaml from what the application serves.
tasks.named<Test>("integrationTest") {
    listOf(
        "ledgerly.test.seed",
        "ledgerly.test.concurrentTransfers",
        "ledgerly.test.deadlockPairs",
        "ledgerly.openapi.update",
    ).forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // The test that compares openapi.yaml with the served document must rerun when the file changes.
    inputs.files(layout.projectDirectory.files("openapi.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":ledger-contracts"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.datasource.micrometer.spring.boot)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.flyway.test)
    testImplementation(libs.spring.boot.starter.kafka.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.archunit.junit5)
    // Sample events of the contract. integrationTest inherits the test dependencies.
    testImplementation(testFixtures(project(":ledger-contracts")))
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.jqwik)
}

// InvariantChecker reads the statements of scripts/invariants.sql, so the tests must rerun when that file changes.
tasks.named<Test>("integrationTest") {
    inputs.file(rootProject.file("scripts/invariants.sql")).withPathSensitivity(PathSensitivity.RELATIVE)
}
