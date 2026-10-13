# Build stage: only the files the build needs (never BRIEF.md, .equipo/ or local build output).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
# curl is only for the container healthcheck; the JRE image ships without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/build/libs/order-sync-service-0.0.1-SNAPSHOT.jar app.jar
USER app
# 8080: application. 8081: management (health, Prometheus scrape), meant for the internal network only.
EXPOSE 8080 8081
# Liveness only: an ERP outage must never make Docker restart the app.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:8081/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
