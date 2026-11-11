# Build stage
FROM gradle:8.11-jdk25 AS builder

WORKDIR /app

# Copy gradle files
COPY settings.gradle .
COPY build.gradle .

# Download dependencies
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src src

# Build
RUN gradle bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Copy built jar
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]