# Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon installDist -x test

# Run
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 tether && mkdir /data && chown tether /data
COPY --from=build /src/build/install/tether-debug /opt/tether-debug
USER tether
ENV TETHER_DEBUG_PORT=8080 \
    TETHER_DEBUG_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/opt/tether-debug/bin/tether-debug"]
