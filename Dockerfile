# Build stage
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# Static ffmpeg/ffprobe (P4-D server-side evidence clip extraction). Copied
# from the canonical static build — no Alpine package fetch at build time
# (the Alpine CDN is the slow path) and no runtime libc dependencies.
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

# Production stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=ffmpeg /ffmpeg /usr/local/bin/ffmpeg
COPY --from=ffmpeg /ffprobe /usr/local/bin/ffprobe

RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=builder /app/target/*.jar app.jar

RUN chown -R spring:spring /app

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
