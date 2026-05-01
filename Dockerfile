# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace

COPY --chown=gradle:gradle settings.gradle build.gradle ./
COPY --chown=gradle:gradle src ./src

RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN chown spring:spring /app/app.jar

USER spring
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
