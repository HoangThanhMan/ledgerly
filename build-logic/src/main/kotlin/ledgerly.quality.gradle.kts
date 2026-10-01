// Chất lượng code, chặn ngay lúc build:
//   - Spotless + Palantir Java Format: format thống nhất. Sửa tự động bằng ./gradlew spotlessApply
//   - Error Prone + NullAway (chế độ JSpecify): lỗi phổ biến và lỗi null là lỗi biên dịch
//   - JaCoCo: báo cáo coverage. Tuần 2 chỉ báo cáo, từ tuần 4 mới đặt ngưỡng
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
        // Chỉ kiểm tra package có @NullMarked, theo ngữ nghĩa JSpecify.
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

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"))
}
