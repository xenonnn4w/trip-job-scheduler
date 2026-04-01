CREATE TABLE tenant (id uuid PRIMARY KEY, name text NOT NULL, zone text NOT NULL, paused boolean NOT NULL DEFAULT false, concurrency_cap integer NOT NULL DEFAULT 8 CHECK (concurrency_cap > 0));
CREATE TABLE app_user (username text PRIMARY KEY, password_hash text NOT NULL, role text NOT NULL CHECK (role IN ('ADMIN','OPERATOR','EMPLOYEE')), tenant_id uuid REFERENCES tenant(id));
CREATE TABLE job (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), kind text NOT NULL CHECK (kind IN ('ONE_OFF','CRON')),
 cron text, next_fire_at timestamptz NOT NULL, payload text NOT NULL, priority integer NOT NULL DEFAULT 0,
 max_run_seconds integer NOT NULL CHECK (max_run_seconds > 0), misfire text NOT NULL CHECK (misfire IN ('FIRE_NOW','SKIP','COALESCE')),
 shard integer NOT NULL, enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX job_due_idx ON job(shard,next_fire_at) WHERE enabled;
CREATE TABLE job_run (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES job(id), tenant_id uuid NOT NULL REFERENCES tenant(id),
 scheduled_at timestamptz NOT NULL, state text NOT NULL CHECK (state IN ('READY','RUNNING','RETRY','SUCCEEDED','DEAD','SKIPPED')),
 attempt integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL, lease_until timestamptz,
 owner text, fence bigint NOT NULL DEFAULT 0, idempotency_key text NOT NULL UNIQUE,
 last_error text, started_at timestamptz, completed_at timestamptz,
 UNIQUE(job_id,scheduled_at)
);
CREATE INDEX job_run_claim_idx ON job_run(available_at,tenant_id) WHERE state IN ('READY','RETRY','RUNNING');
CREATE INDEX job_run_tenant_queue_idx ON job_run(tenant_id,scheduled_at) WHERE state IN ('READY','RETRY','RUNNING');
CREATE TABLE effect (idempotency_key text PRIMARY KEY, run_id uuid NOT NULL UNIQUE REFERENCES job_run(id), tenant_id uuid NOT NULL, payload text NOT NULL, committed_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE worker_node (id text PRIMARY KEY, heartbeat_at timestamptz NOT NULL);
CREATE TABLE tenant_slot (tenant_id uuid NOT NULL REFERENCES tenant(id), slot integer NOT NULL, run_id uuid, lease_until timestamptz, PRIMARY KEY(tenant_id,slot));
