FROM eclipse-temurin:26-jdk-noble AS builder
WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends curl zip unzip bash && \
    curl -s "https://get.sdkman.io" | bash && \
    bash -c "source /root/.sdkman/bin/sdkman-init.sh && sdk install maven 3.9.16"

COPY pom.xml ./
RUN bash -c "source /root/.sdkman/bin/sdkman-init.sh && mvn dependency:go-offline -q -B"

COPY src/ src/
RUN bash -c "source /root/.sdkman/bin/sdkman-init.sh && mvn package -DskipTests -q -B"

FROM eclipse-temurin:26-jre-alpine AS runtime
WORKDIR /app

COPY --from=builder /build/target/kiro-gateway-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
