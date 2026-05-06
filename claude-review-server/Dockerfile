# ── 빌드 스테이지 ──────────────────────────────────────────────────────
FROM gradle:8.12-jdk21-alpine AS build
WORKDIR /app
COPY gradle ./gradle
COPY gradlew ./
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN chmod +x gradlew && ./gradlew clean bootJar --no-daemon -x test

# ── 런타임 스테이지 ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S reviewer && adduser -S reviewer -G reviewer

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

RUN mkdir -p /app/review-logs && chown -R reviewer:reviewer /app
USER reviewer

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
