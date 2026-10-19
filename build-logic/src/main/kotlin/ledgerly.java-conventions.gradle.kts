// Java settings shared by every module.
plugins {
    java
    id("ledgerly.quality")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
