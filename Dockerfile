# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY frontend ./frontend
# Windows checkouts can contain CRLF. Match Spotless's Linux line endings inside the image.
RUN sed -i 's/\r$//' pom.xml && find src -name '*.java' -exec sed -i 's/\r$//' {} +
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package \
    && test -s target/classes/static/web/index.html

FROM eclipse-temurin:21-jre-noble AS app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/knowledge-assistant-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
