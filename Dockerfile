# syntax=docker/dockerfile:1

# One image definition for all the applications of the repository. Pick the application with a build argument:
#
#   docker build --build-arg MODULE=ledger-app -t ledger-app .
#
# MODULE is the name of a Gradle module that builds a Spring Boot jar: ledger-app, notification-consumer or
# mock-bank. compose.yaml builds the images of its "full" profile this way.

# ---- build: compiles every application once. The images of all modules share this stage and its cache.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# The cache mount keeps the Gradle distribution and the downloaded dependencies from one build to the next.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -Dorg.gradle.jvmargs="-Xmx1g -Dfile.encoding=UTF-8"

# ---- extract: splits the jar into layers, so the dependencies (most of the bytes, rarely changed) and the
# application's own classes (few bytes, changed by every commit) end up in different image layers.
FROM eclipse-temurin:25-jre-alpine AS extract
ARG MODULE
WORKDIR /builder
COPY --from=build /src/${MODULE}/build/libs/ libs/
# Next to the executable jar Gradle also builds a "-plain" jar without the dependencies. Take the executable one.
RUN mv "$(ls libs/*.jar | grep -v -- '-plain\.jar$')" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- run
FROM eclipse-temurin:25-jre-alpine
RUN adduser -S -u 1001 app
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
# Training run for the AOT cache: start the application context, record the classes it loads and links, and exit.
# It runs here, in the final image, because the cache is only valid for the same JDK and the same class path.
# Nothing else is reachable while an image is built, so the aot-training profile turns off whatever would
# connect to the database or to Kafka during startup.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.profiles.active=aot-training \
        -jar application.jar \
    && rm -f app.aot.config
USER app
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
