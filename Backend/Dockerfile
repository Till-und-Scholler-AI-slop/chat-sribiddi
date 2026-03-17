FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=build /app/build/libs/*.jar /app/app.jar

RUN chown -R appuser:appuser /app

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", \
  "-jar", "/app/app.jar"]
