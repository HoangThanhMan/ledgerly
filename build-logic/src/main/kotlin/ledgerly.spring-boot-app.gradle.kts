// Module ứng dụng Spring Boot: phiên bản dependency lấy từ Spring Boot BOM.
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

// Cấu hình Testcontainers và lớp Test<App>Application nằm ở src/integrationTest/java, dùng chung với
// integration test. Vì vậy bootTestRun (chạy app với Testcontainers, không cần compose) lấy classpath
// của suite integrationTest thay vì suite test. Suite test chỉ chứa unit test.
val integrationTestSourceSet = sourceSets.named("integrationTest")
tasks.named<ResolveMainClassName>("resolveTestMainClassName") {
    setClasspath(integrationTestSourceSet.map { it.output })
}
tasks.named<BootRun>("bootTestRun") {
    classpath = integrationTestSourceSet.get().runtimeClasspath
}
