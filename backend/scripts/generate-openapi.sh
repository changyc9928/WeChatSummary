#!/usr/bin/env bash
#
# Regenerates backend/openapi.yaml from the running Spring Boot application.
#
# Prerequisites:
#   - Backend is running (e.g. `docker compose up -d` + `./mvnw spring-boot:run` from backend/)
#
# Usage:
#   backend/scripts/generate-openapi.sh
#
# After refreshing the spec, regenerate the frontend client:
#   cd frontend && npm run generate:api
set -euo pipefail

BASE_URL="${OPENAPI_BASE_URL:-http://localhost:8080}"
OUTPUT_FILE="$(cd "$(dirname "$0")/.." && pwd)/openapi.yaml"

if ! curl -sf "${BASE_URL}/v3/api-docs.yaml" -o "${OUTPUT_FILE}"; then
  echo "Backend not reachable at ${BASE_URL}." >&2
  echo "Start it first (docker compose up -d && ./mvnw spring-boot:run) and retry." >&2
  exit 1
fi

echo "openapi.yaml updated from ${BASE_URL}/v3/api-docs.yaml"
