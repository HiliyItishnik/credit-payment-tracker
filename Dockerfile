FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn -B --no-transfer-progress verify

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home tracker
WORKDIR /app
COPY --from=build --chown=tracker:tracker /workspace/target/credit-payment-tracker-1.0.0.jar app.jar
USER tracker
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
