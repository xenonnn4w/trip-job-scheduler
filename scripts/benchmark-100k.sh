#!/usr/bin/env bash
# Run the case-study workload against an already running three-node cluster.
set -euo pipefail
cd "$(dirname "$0")/.."

COUNT=${COUNT:-100000}
WINDOW_SECONDS=${WINDOW_SECONDS:-600}
START_DELAY_SECONDS=${START_DELAY_SECONDS:-45}
TENANTS=${TENANTS:-100}
[[ "$COUNT" =~ ^[1-9][0-9]*$ && "$WINDOW_SECONDS" =~ ^[1-9][0-9]*$ && "$START_DELAY_SECONDS" =~ ^[1-9][0-9]*$ && "$TENANTS" =~ ^[1-9][0-9]*$ ]] || { echo 'COUNT, WINDOW_SECONDS, START_DELAY_SECONDS, and TENANTS must be positive integers' >&2; exit 2; }
BATCH="bench-$(uuidgen | tr '[:upper:]' '[:lower:]')"
if [[ -n "${BENCH_DB_CONTAINER:-}" ]]; then
  DB=(docker exec -i "$BENCH_DB_CONTAINER" psql -U scheduler -d scheduler -v ON_ERROR_STOP=1)
else
  DB=(docker compose exec -T postgres psql -U scheduler -d scheduler -v ON_ERROR_STOP=1)
fi

NODES=$("${DB[@]}" -Atc "SELECT count(*) FROM worker_node WHERE heartbeat_at > clock_timestamp() - interval '5 seconds'")
if [[ "$NODES" -ne 3 ]]; then
  echo "Expected three active nodes; found $NODES" >&2
  exit 1
fi

echo "Cohort: $BATCH; jobs: $COUNT; tenants: $TENANTS; window: ${WINDOW_SECONDS}s; active nodes: $NODES"
"${DB[@]}" -v batch="$BATCH" -v job_count="$COUNT" -v tenant_count="$TENANTS" -v window="$WINDOW_SECONDS" -v delay="$START_DELAY_SECONDS" <<'SQL'
CREATE TEMP TABLE bench_tenants AS
SELECT g, gen_random_uuid() AS id FROM generate_series(1, :tenant_count) g;
INSERT INTO tenant(id,name,zone,concurrency_cap)
SELECT id, :'batch' || '-' || g, 'UTC', 8 FROM bench_tenants;
INSERT INTO tenant_slot(tenant_id,slot)
SELECT id, s FROM bench_tenants CROSS JOIN generate_series(0,7) s;
CREATE TEMP TABLE bench_start AS SELECT clock_timestamp() + (:delay * interval '1 second') AS at;
INSERT INTO job(id,tenant_id,kind,next_fire_at,payload,priority,max_run_seconds,misfire,shard)
SELECT gen_random_uuid(), t.id, 'ONE_OFF', b.at + (series.g * :window::numeric / :job_count) * interval '1 second',
       'benchmark', 0, 60, 'FIRE_NOW', series.g % 256
FROM generate_series(0, :job_count - 1) series(g)
JOIN bench_tenants t ON t.g = (series.g % :tenant_count) + 1
CROSS JOIN bench_start b;
SELECT min(next_fire_at) AS first_due, max(next_fire_at) AS last_due
FROM job WHERE tenant_id IN (SELECT id FROM bench_tenants);
SQL

DEADLINE=$(( $(date +%s) + START_DELAY_SECONDS + WINDOW_SECONDS + 600 ))
while true; do
  NODES=$("${DB[@]}" -Atc "SELECT count(*) FROM worker_node WHERE heartbeat_at > clock_timestamp() - interval '5 seconds'")
  if [[ "$NODES" -ne 3 ]]; then
    echo "Benchmark invalid: active node count changed to $NODES" >&2
    exit 1
  fi
  COUNTS=$("${DB[@]}" -Atc "SELECT count(*) FILTER (WHERE r.state='SUCCEEDED'), count(*) FILTER (WHERE r.state='DEAD'), count(*) FROM job_run r JOIN tenant t ON t.id=r.tenant_id WHERE t.name LIKE '$BATCH-%'")
  IFS='|' read -r DONE DEAD TOTAL <<< "$COUNTS"
  echo "$(date -Is) materialized=$TOTAL succeeded=$DONE dead=$DEAD"
  if (( DONE + DEAD >= COUNT || $(date +%s) >= DEADLINE )); then break; fi
  sleep 10
done

"${DB[@]}" -v batch="$BATCH" <<'SQL'
SELECT count(*) AS jobs, count(*) FILTER (WHERE NOT j.enabled) AS materialized,
       count(r.id) FILTER (WHERE r.state='SUCCEEDED') AS succeeded,
       count(r.id) FILTER (WHERE r.state='DEAD') AS dead,
       count(e.run_id) AS committed_effects,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM r.started_at-r.scheduled_at)))::numeric,3) AS p50_fire_lag_s,
       round((percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM r.started_at-r.scheduled_at)))::numeric,3) AS p99_fire_lag_s,
       round(max(extract(epoch FROM r.started_at-r.scheduled_at))::numeric,3) AS max_fire_lag_s
FROM job j JOIN tenant t ON t.id=j.tenant_id
LEFT JOIN job_run r ON r.job_id=j.id
LEFT JOIN effect e ON e.run_id=r.id
WHERE t.name LIKE :'batch' || '-%';
SQL
if (( DONE != COUNT || DEAD != 0 )); then exit 1; fi
P99=$("${DB[@]}" -Atc "SELECT percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM r.started_at-r.scheduled_at)) FROM job_run r JOIN tenant t ON t.id=r.tenant_id WHERE t.name LIKE '$BATCH-%'")
if ! awk -v p99="$P99" 'BEGIN { exit !(p99 < 2) }'; then
  echo "FAIL: p99 fire lag ${P99}s is not below 2s" >&2
  exit 1
fi
echo "PASS: $COUNT jobs succeeded on three nodes with p99 fire lag ${P99}s (<2s)"
