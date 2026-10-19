// integrationTest suite: tests that need real infrastructure (Testcontainers), in src/integrationTest/java.
// Kept apart from the test suite so ./gradlew test runs only fast unit tests. ./gradlew check runs both.
plugins {
    java
    jacoco
}

val integrationTest = testing.suites.register<JvmTestSuite>("integrationTest") {
    dependencies {
        implementation(project())
    }
    targets.all {
        testTask.configure {
            shouldRunAfter(tasks.named("test"))
        }
    }
}

// Integration tests reuse the unit test dependencies (starter test, AssertJ...).
configurations.named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }

tasks.named("check") {
    dependsOn(integrationTest)
}

// The coverage report and the coverage gate merge unit and integration test results.
val coverageData = fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") }
tasks.named<JacocoReport>("jacocoTestReport") {
    executionData.setFrom(coverageData)
    mustRunAfter(tasks.named("integrationTest"))
}
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    executionData.setFrom(coverageData)
    mustRunAfter(tasks.named("integrationTest"))
}
