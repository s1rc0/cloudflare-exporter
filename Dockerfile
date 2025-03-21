# Dockerfile
FROM eclipse-temurin:17-jdk-alpine as builder

WORKDIR /app

COPY . .

RUN ./sbt clean assembly

# Final container
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/cloudflare-exporter-assembly-0.1.0-SNAPSHOT.jar /app/exporter.jar

ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "/app/exporter.jar"]