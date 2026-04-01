#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${APP_BOOTSTRAP_ADMIN_PASSWORD:?Set APP_BOOTSTRAP_ADMIN_PASSWORD to the running cluster admin password}"
N=${1:-1000}
[[ "$N" =~ ^[0-9]+$ ]] || { echo "N must be a positive integer" >&2; exit 2; }
BASE=${BASE_URL:-http://localhost:8080}
TENANT=$(curl -fsS -u "admin:$APP_BOOTSTRAP_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"name":"Benchmark","zone":"UTC","concurrencyCap":128}' "$BASE/api/admin/tenants" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
JOB=$(curl -fsS -u "admin:$APP_BOOTSTRAP_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"kind":"ONE_OFF","at":"2030-01-01T00:00:00Z","payload":"benchmark","priority":0,"maxRunSeconds":60,"misfire":"FIRE_NOW"}' \
  "$BASE/api/tenants/$TENANT/jobs" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
docker compose exec -T postgres psql -U scheduler -d scheduler -v ON_ERROR_STOP=1 -c \
  "INSERT INTO job_run(id,job_id,tenant_id,scheduled_at,state,available_at,idempotency_key) SELECT gen_random_uuid(),'$JOB','$TENANT',clock_timestamp()+g*interval '1 microsecond','READY',clock_timestamp(),'bench-$TENANT-'||g FROM generate_series(1,$N) g" >/dev/null
for _ in $(seq 1 180); do
  DONE=$(docker compose exec -T postgres psql -U scheduler -d scheduler -Atc "SELECT count(*) FROM job_run WHERE tenant_id='$TENANT' AND state='SUCCEEDED'")
  [[ "$DONE" -ge "$N" ]] && break
  sleep 1
done
docker compose exec -T postgres psql -U scheduler -d scheduler -c \
  "SELECT count(*) AS completed,round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM started_at-scheduled_at)))::numeric,3) AS p50_lag_s,round((percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM started_at-scheduled_at)))::numeric,3) AS p99_lag_s,round((count(*)/NULLIF(extract(epoch FROM max(completed_at)-min(started_at)),0))::numeric,1) AS runs_per_s FROM job_run WHERE tenant_id='$TENANT' AND state='SUCCEEDED'"
