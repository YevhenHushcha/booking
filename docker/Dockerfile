# syntax=docker/dockerfile:1
# One Dockerfile for every application module; the module is picked with --build-arg MODULE=...
# Build context is the project root, because a Maven reactor build needs the sibling poms.

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl ${MODULE} -am -DskipTests package && \
    cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:8-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# Containers get a memory limit, not a machine; let the JVM size its heap from the cgroup
# (Java 8 understands cgroup v2 limits from 8u372 on).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
