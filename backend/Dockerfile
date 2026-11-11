FROM gradle:9.2.1-jdk25 AS build
WORKDIR /app

COPY settings.gradle build.gradle ./
COPY src ./src

RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 app \
    && mkdir -p /app/pdf-exports \
    && chown -R app /app

WORKDIR /app

COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]