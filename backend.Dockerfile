# syntax=docker/dockerfile:1.7

FROM gradle:8-jdk21 AS build
WORKDIR /src

COPY . .

# Keep temporary JVM perfdata files out of Kaniko's post-build snapshot.
RUN JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:-UsePerfData" \
    ./gradlew :backend:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/backend/build/install/backend /app

ENV SOUZ_BACKEND_HOST=0.0.0.0
ENV SOUZ_BACKEND_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

CMD ["/app/bin/backend"]
