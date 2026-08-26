# syntax=docker/dockerfile:1

FROM node:20-bookworm-slim AS frontend-build
WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
COPY --from=frontend-build /app/frontend/dist /tmp/frontend-dist

# Keep backend-served simulation assets while refreshing the React bundle.
RUN cp -R /tmp/frontend-dist/. src/main/resources/static/ \
    && mvn -q package -DskipTests \
    && cp target/roleplay-engine-1.0.0-SNAPSHOT.jar /tmp/roleplay-engine.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ENV SERVER_PORT=8000
EXPOSE 8000
VOLUME ["/app/data"]

COPY --from=backend-build /tmp/roleplay-engine.jar /app/roleplay-engine.jar

ENTRYPOINT ["java", "-jar", "/app/roleplay-engine.jar"]
