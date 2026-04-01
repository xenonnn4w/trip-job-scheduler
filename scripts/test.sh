#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose --profile test up -d --wait testdb
mkdir -p .m2-cache
docker run --rm --network host --user "$(id -u):$(id -g)" -e MAVEN_OPTS=-Duser.home=/tmp \
  -v "$PWD":/app -v "$PWD/.m2-cache":/tmp/.m2 -w /app \
  -e TEST_DB_URL=jdbc:postgresql://localhost:55432/scheduler_test \
  maven:3.9.11-eclipse-temurin-21 mvn test
