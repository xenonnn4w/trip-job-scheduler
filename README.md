# Distributed Trip Job Scheduler

A Java 21, Spring Boot, and PostgreSQL backend for scheduling one-off and recurring jobs across three worker nodes. It supports tenant time zones, sharded work assignment, fenced leases, retries, tenant concurrency limits, and an idempotent database effect ledger.

The project includes a browser control room and the same operations are available through the HTTP API. See [DESIGN.md](DESIGN.md) for the architecture, failure handling, guarantees, and trade-offs.

## Prerequisites

- Docker Engine and Docker Compose (`docker compose version`)
- A Bash shell, `curl`, and Python 3 for the copyable example commands
- Ports **8080**, **8081**, **8082**, and **5432** available on your machine

You do not need to install Java, Maven, or PostgreSQL for the Docker setup. Run all commands from the project directory.

## Start the project

```bash
read -rsp 'Choose an admin password: ' ADMIN_PASSWORD; echo
export ADMIN_PASSWORD
APP_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/actuator/health
```

The first build downloads the Java and PostgreSQL images and Maven dependencies, so it can take a few minutes. When ready, the health request returns `{"status":"UP"}`. Node 1 serves the examples at `http://localhost:8080`; nodes 2 and 3 serve the same API on ports 8081 and 8082. All three share one PostgreSQL database.

The first startup creates the HTTP Basic user `admin` with `ADMIN_PASSWORD`. The password is stored as a BCrypt hash. **Changing the environment variable later does not change the password of an existing admin account.** Keep the password out of screenshots. Use HTTPS in front of the API before exposing it outside your machine.

## Use the dashboard

Open **http://localhost:8080** and sign in as `admin` with the password you chose above. The dashboard lets you create or select a tenant, create recurring or one-off jobs, trigger a run, inspect recent runs and effect counts, pause or resume the tenant, and replay dead-letter runs. Data refreshes every 15 seconds, or you can use the refresh button. Operator and employee accounts see only their own tenant; employees have read-only controls. Credentials are held only in the current browser tab and are cleared on sign-out or tab close.

The dashboard is served by Spring Boot from the same origin as the API, so no frontend build or separate development server is needed. The `effect` shown in the UI is a database record, not a real cab alert.

## Try the API

The following commands create a tenant, schedule a recurring job, trigger it immediately, and inspect its run. Keep `ADMIN_PASSWORD` from the startup step in the same shell.

### 1. Create a tenant

```bash
BASE=http://localhost:8080
TENANT=$(curl -fsS -u "admin:$ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo client","zone":"Asia/Kolkata","concurrencyCap":8}' \
  "$BASE/api/admin/tenants" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "$TENANT"
```

`zone` is an IANA time zone. `concurrencyCap` limits how many runs this tenant can execute at once (1 to 128).

### 2. Create a recurring job

```bash
JOB=$(curl -fsS -u "admin:$ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"CRON","cron":"0 0 22 * * *","payload":"roster-generation","priority":1,"maxRunSeconds":60,"misfire":"COALESCE"}' \
  "$BASE/api/tenants/$TENANT/jobs" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "$JOB"
curl -fsS -u "admin:$ADMIN_PASSWORD" "$BASE/api/tenants/$TENANT/jobs/$JOB/next?count=5"
```

The six-field cron expression above schedules 10:00 PM every day in the tenant's time zone. `misfire` can be `FIRE_NOW`, `SKIP`, or `COALESCE`. A one-off job uses `"kind":"ONE_OFF"` and an ISO-8601 timestamp such as `"at":"2030-01-01T16:30:00Z"` instead of `cron`.

### 3. Trigger and inspect a run

```bash
RUN=$(curl -fsS -u "admin:$ADMIN_PASSWORD" -X POST \
  "$BASE/api/tenants/$TENANT/jobs/$JOB/trigger" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])')
echo "$RUN"
curl -fsS -u "admin:$ADMIN_PASSWORD" "$BASE/api/tenants/$TENANT/runs/$RUN"
curl -fsS -u "admin:$ADMIN_PASSWORD" "$BASE/api/tenants/$TENANT/runs/$RUN/effects"
```

The run state moves from `READY` to `RUNNING` to `SUCCEEDED`; the last request then returns `{"count":1}`. If it is still processing, wait a second and repeat the two `GET` requests.

**The effect is a row in PostgreSQL's `effect` table.** This demo does not send real cab alerts or call another provider. A real provider must deduplicate the run's idempotency key to preserve the exactly-once effect guarantee outside this database.

## Other useful requests

The examples below use the `BASE` and `TENANT` values created above.

| Action | Request |
| --- | --- |
| Pause a tenant | `curl -u "admin:$ADMIN_PASSWORD" -X POST "$BASE/api/tenants/$TENANT/pause"` |
| Resume a tenant | `curl -u "admin:$ADMIN_PASSWORD" -X POST "$BASE/api/tenants/$TENANT/resume"` |
| List dead-letter runs | `curl -u "admin:$ADMIN_PASSWORD" "$BASE/api/tenants/$TENANT/dead"` |
| Replay a dead-letter run | `curl -u "admin:$ADMIN_PASSWORD" -X POST "$BASE/api/tenants/$TENANT/dead/RUN_ID/replay"` |
| Health check | `curl "$BASE/actuator/health"` |
| Prometheus metrics | `curl -u "admin:$ADMIN_PASSWORD" "$BASE/actuator/prometheus"` |

To create a tenant-scoped operator or employee, an admin can call `POST /api/admin/users`:

```bash
curl -fsS -u "admin:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d "{\"username\":\"demo-operator\",\"password\":\"replace-with-12-plus-characters\",\"role\":\"OPERATOR\",\"tenantId\":\"$TENANT\"}" \
  "$BASE/api/admin/users"
```

`ADMIN` can manage all tenants. `OPERATOR` can manage jobs and dead letters for its tenant. `EMPLOYEE` can read its tenant's next fire times and run status. Cross-tenant requests return 404.

## Tests and benchmark

Run the unit and PostgreSQL integration tests with:

```bash
bash scripts/test.sh
```

The script starts a separate test database on port **55432** and runs Maven in a Docker container. The suite covers daylight-saving transitions, shard redistribution, concurrent claims, stale fencing tokens, tenant limits, retries, dead-letter replay, and tenant isolation.

With the application running, you can measure a scheduler-only burst:

```bash
APP_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" bash scripts/benchmark.sh 1000
```

This benchmark inserts ready runs directly into PostgreSQL, so it excludes API creation time. On one development machine (Intel Core i5-12450HX, 12 logical CPUs, 23 GiB RAM, PostgreSQL 16.13, Java 21, one worker node), 1,000 simultaneous runs completed at **120.2 runs/s**, with **7.051 s p50** and **9.286 s p99** fire lag. This is a burst test with a different arrival pattern from the case study workload.

To exercise the case-study workload, start all three nodes (`docker compose up --build`), then run:

```bash
bash scripts/benchmark-100k.sh
```

This creates **100,000 distinct one-off jobs** for 100 tenants, evenly distributes their due times over a **10-minute window**, and waits for their real scheduler runs. It reports materialized, succeeded, dead, and effect counts, plus p50/p99/max fire lag. It seeds jobs directly in PostgreSQL so the measurement covers scheduling and execution rather than HTTP creation. Run it against a fresh database or an otherwise idle cluster for a comparable result. Each node has 16 execution threads (48 across three nodes); each benchmark tenant allows eight concurrent runs. The target is all 100,000 successful with p99 fire lag below two seconds. The earlier 1,000-run burst is a different workload and does not establish this target.

**Measured on 30 October 2026:** three local Java 21 worker processes and an isolated PostgreSQL 16 container on the machine above completed **100,000/100,000 jobs** with **100,000 committed effects** and **0 dead runs**. Fire lag (`started_at - scheduled_at`, using PostgreSQL timestamps) was **0.151 s p50**, **0.694 s p99**, and **1.532 s maximum**. The three workers executed 33,594, 33,203, and 33,203 runs respectively. Each of the 100 tenants contributed 1,000 jobs. The jobs were due from 15:22:43 to 15:32:43 UTC; the last completed at 15:32:43 UTC. This meets the stated 100,000 jobs/10 minutes/three nodes/p99 under two seconds workload on this machine. It does not establish performance for a different machine or a simultaneous 100,000-job burst.

## Stop and troubleshoot

```bash
docker compose logs -f node1
docker compose stop
```

`docker compose stop` preserves the containers and their database contents for the next `docker compose start`. **`docker compose down` removes the PostgreSQL container and its data**, because this demo does not configure a persistent volume.

If startup fails, check `docker compose ps` and `docker compose logs postgres node1`. Port conflicts are common; free ports 5432 and 8080-8082 before retrying. If authentication fails after changing `ADMIN_PASSWORD`, remember that an existing database still has the password from its first startup.
