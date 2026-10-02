#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
./gradlew :backend:installDist :backend:testClasses --console=plain

smoke_name="souz-knowledge-smoke-$$"
cleanup() {
    docker rm -f "$smoke_name" >/dev/null 2>&1 || true
    docker network rm "$smoke_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create "$smoke_name" >/dev/null
docker run -d --name "$smoke_name" --network "$smoke_name" --network-alias postgres \
    -e POSTGRES_DB=souz -e POSTGRES_USER=souz -e POSTGRES_PASSWORD=souz \
    postgres:16-alpine >/dev/null
for attempt in {1..30}; do
    if docker exec "$smoke_name" pg_isready -U souz -d souz >/dev/null; then break; fi
    sleep 1
done
docker exec "$smoke_name" pg_isready -U souz -d souz

# Separate JVMs demonstrate persistence across application replacement. Only PostgreSQL is writable.
for phase in write read-clear; do
    docker run --rm --read-only --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges \
        --network "$smoke_name" \
        --mount "type=bind,source=$repo_dir/backend/build/install/backend/lib,target=/app/lib,readonly" \
        --mount "type=bind,source=$repo_dir/backend/build/classes/kotlin/test,target=/app/test,readonly" \
        eclipse-temurin:21-jre java -XX:-UsePerfData -cp '/app/lib/*:/app/test' \
        ru.souz.backend.storage.postgres.KnowledgeReadOnlySmokeKt \
        "$phase" 'jdbc:postgresql://postgres:5432/souz?targetServerType=primary'
done
