// Spring Boot application module. Dependency versions are managed by the Spring Boot BOM.
import org.springframework.boot.gradle.plugin.ResolveMainClassName
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("ledgerly.java-conventions")
    id("ledgerly.integration-test")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The Testcontainers configuration and the Test<App>Application class live in src/integrationTest/java,
// shared with the integration tests. So bootTestRun (runs the app against Testcontainers, no compose needed)
// uses the integrationTest classpath instead of the test one. The test suite holds unit tests only.
val integrationTestSourceSet = sourceSets.named("integrationTest")
tasks.named<ResolveMainClassName>("resolveTestMainClassName") {
    setClasspath(integrationTestSourceSet.map { it.output })
}
tasks.named<BootRun>("bootTestRun") {
    classpath = integrationTestSourceSet.get().runtimeClasspath
}
