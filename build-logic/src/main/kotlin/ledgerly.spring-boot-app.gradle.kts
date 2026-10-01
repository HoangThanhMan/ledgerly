// Module ứng dụng Spring Boot: phiên bản dependency lấy từ Spring Boot BOM.
plugins {
    id("ledgerly.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
