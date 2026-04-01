# Trip job scheduler (Java 21 / Spring Boot / PostgreSQL)

Implements the distributed scheduler from the supplied case study. See [DESIGN.md](DESIGN.md) for guarantees, failure cases, complexity, and limits.

## Run

```bash
APP_BOOTSTRAP_ADMIN_PASSWORD='choose-a-long-secret' docker compose up --build
```

The API is at `http://localhost:8080` (the other worker nodes listen on 8081 and 8082). PostgreSQL is included. Health: `/actuator/health`; Prometheus: `/actuator/prometheus` (admin authentication required). To run the full test suite with Docker available:

```bash
bash scripts/test.sh
```

The first boot creates an `admin` user with `APP_BOOTSTRAP_ADMIN_PASSWORD`. Change it before any network exposure. The API uses HTTP Basic; terminate TLS at a reverse proxy in production. Admins can create tenants and users. Operators can create/trigger jobs, pause/resume their tenant, inspect/replay dead runs. Employees can view their tenant's run status and next fire times. Tenant access is checked on every tenant-scoped request; cross-tenant resources return 404.

## Example flow

```bash
BASE=http://localhost:8080
AUTH='admin:choose-a-long-secret'
TENANT=$(curl -sf -u "$AUTH" -H 'Content-Type: application/json' -d '{"name":"Example","zone":"Asia/Kolkata","concurrencyCap":8}' "$BASE/api/admin/tenants" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
JOB=$(curl -sf -u "$AUTH" -H 'Content-Type: application/json' -d '{"kind":"CRON","cron":"0 0 22 * * *","payload":"roster-generation","priority":1,"maxRunSeconds":60,"misfire":"COALESCE"}' "$BASE/api/tenants/$TENANT/jobs" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -sf -u "$AUTH" "$BASE/api/tenants/$TENANT/jobs/$JOB/next?count=5"
RUN=$(curl -sf -u "$AUTH" -X POST "$BASE/api/tenants/$TENANT/jobs/$JOB/trigger" | python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])')
curl -sf -u "$AUTH" "$BASE/api/tenants/$TENANT/runs/$RUN"
curl -sf -u "$AUTH" "$BASE/api/tenants/$TENANT/runs/$RUN/effects"
```

One-off jobs use `"kind":"ONE_OFF"` and an ISO-8601 UTC `"at"` instead of `cron`. A run's effect count reaches 1 after a worker commits it. The `effect` table models the downstream action; the payload is intentionally not sent to a live provider.

## Verification

Unit tests cover daylight-saving transitions, misfires, shard redistribution, and backoff. PostgreSQL integration tests cover concurrent claims, an expired lease followed by a higher fence, rejection of the stale writer, a single effect commit, tenant concurrency, retries, dead-letter replay, and tenant isolation.

For a scheduler-only burst benchmark after `docker compose up`, run `APP_BOOTSTRAP_ADMIN_PASSWORD=... bash scripts/benchmark.sh 1000`. It inserts ready runs directly into PostgreSQL, excluding API insertion time. On this development machine (Intel Core i5-12450HX, 12 logical CPUs, 23 GiB RAM, local PostgreSQL 16.13, Java 21, one worker node, 16 worker threads), 1,000 simultaneously ready runs completed at **120.2 runs/s**, with **7.051 s p50** and **9.286 s p99 fire lag**. This does **not** meet the case study's p99 <2 s target for a simultaneous burst. The 100,000-run/10-minute, three-node target has not been measured and is not claimed. Throughput depends strongly on arrival distribution and database hardware.

## Operational notes

The Prometheus endpoint exposes `scheduler_fire_lag_seconds` histogram, `scheduler_running`, `scheduler_retrying`, `scheduler_dead`, `scheduler_queue`, `scheduler_errors_total`, `scheduler_retries_total`, and `scheduler_fence_rejections_total`. Logs include run IDs and attempt numbers, never payloads. The API emits generic validation and conflict errors to avoid leaking employee data. No cache is used for mutable scheduling decisions; the in-memory shard ownership snapshot refreshes every second and may be stale for at most roughly the heartbeat window.
