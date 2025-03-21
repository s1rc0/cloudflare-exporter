# Dockerfile

FROM eclipse-temurin:17-jdk-alpine as builder

WORKDIR /app

# Install sbt and other dependencies
RUN apk add --no-cache curl bash zip && \
    curl -Ls https://github.com/sbt/sbt/releases/download/v1.9.7/sbt-1.9.7.tgz | tar xz -C /usr/local && \
    ln -s /usr/local/sbt/bin/sbt /usr/bin/sbt

COPY . .

RUN sbt clean assembly

# Final container
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/cloudflare-exporter-assembly-0.1.0-SNAPSHOT.jar /app/exporter.jar

ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "/app/exporter.jar"]