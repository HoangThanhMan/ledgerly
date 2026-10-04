// Code quality checks that run as part of the build:
//   - Spotless + Palantir Java Format: consistent formatting. Fix automatically with ./gradlew spotlessApply
//   - Error Prone + NullAway (JSpecify mode): common bug patterns and null errors are compile errors
//   - JaCoCo: coverage reports, and a minimum line coverage of 80% for the domain packages
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
    implementation(libs.findLibrary("jspecify").get())
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.findVersion("palantir-java-format").get().requiredVersion)
        formatAnnotations()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // Only check packages annotated with @NullMarked, following JSpecify semantics.
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

// The domain packages hold the money rules, so their line coverage is a gate and not just a number in a report.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("dev.ledgerly.*.internal.domain")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"), tasks.named("jacocoTestCoverageVerification"))
}
