# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies in their own layer: a change under src/ does not download them again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S attendance && adduser -S attendance -G attendance
COPY --from=build /build/target/*.jar app.jar
RUN chown attendance:attendance app.jar
USER attendance

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
