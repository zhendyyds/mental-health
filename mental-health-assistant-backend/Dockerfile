# syntax=docker/dockerfile:1
FROM maven:3.9.15-eclipse-temurin-17@sha256:527989ca6d3279cc9494d665f617374c428eccd7248dbadf0d03bceaa70b9c5d AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469
RUN groupadd --gid 10001 healthagent \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin healthagent \
    && install -d -o 10001 -g 10001 /app /app/upload \
    && command -v wget >/dev/null
WORKDIR /app
COPY --from=build /build/target/mental-health-assistant-backend-0.0.1-SNAPSHOT.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=container \
    FILE_UPLOAD_DIR=/app/upload \
    SERVER_PORT=8082 \
    TZ=Asia/Shanghai
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
