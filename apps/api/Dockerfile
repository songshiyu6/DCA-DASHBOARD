FROM gradle:8.14.3-jdk21 AS build

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG GRADLE_ARGS

WORKDIR /workspace
COPY apps/api/ ./apps/api/
WORKDIR /workspace/apps/api
RUN HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" gradle --no-daemon $GRADLE_ARGS clean bootJar

FROM eclipse-temurin:21-jre-jammy

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY

RUN HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
COPY --from=build /workspace/apps/api/build/libs/dca-terminal-api.jar /app/dca-terminal-api.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
    CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/dca-terminal-api.jar"]
