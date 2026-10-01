// Suite integrationTest: test cần hạ tầng thật (Testcontainers), nằm ở src/integrationTest/java.
// Tách khỏi suite test để ./gradlew test chỉ chạy unit test nhanh. ./gradlew check chạy cả hai.
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

// Integration test dùng chung các dependency test (starter test, AssertJ...) với unit test.
configurations.named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }

tasks.named("check") {
    dependsOn(integrationTest)
}

// Báo cáo coverage gộp cả unit test và integration test.
tasks.named<JacocoReport>("jacocoTestReport") {
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
    mustRunAfter(tasks.named("integrationTest"))
}
