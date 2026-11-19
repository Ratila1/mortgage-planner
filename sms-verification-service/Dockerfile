# Build stage
FROM gradle:9.2.1-jdk25 AS builder
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Copy built jar
COPY --from=builder /app/build/libs/*.jar app.jar

# Healthcheck (bash есть в eclipse-temurin:25-jre, wget — нет)
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8091' || exit 1

USER appuser
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]