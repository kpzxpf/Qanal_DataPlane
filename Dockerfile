# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/

RUN ./gradlew shadowJar --no-daemon -q 2>/dev/null || ./gradlew jar --no-daemon -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S qanal && adduser -S qanal -G qanal

# Storage dir (mount a volume here in production)
RUN mkdir -p /data/qanal-storage && chown qanal:qanal /data/qanal-storage

USER qanal

COPY --from=builder /build/build/libs/*.jar app.jar

# QUIC upload port, QUIC download port, Prometheus metrics
EXPOSE 4433/udp 4434/udp 9091

ENV QANAL_STORAGE_DIR=/data/qanal-storage

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
