package com.moveinsync.scheduler.db;

import com.moveinsync.scheduler.core.*;
import com.moveinsync.scheduler.model.*;
import com.github.benmanes.caffeine.cache.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SchedulerStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    private final int shards;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final Cache<UUID,ZoneId> tenantZones=Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public SchedulerStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, TransactionTemplate tx,
                          @Value("${app.shards}") int shards, @Value("${app.lease-seconds}") int leaseSeconds,
                          @Value("${app.max-attempts}") int maxAttempts) {
        this.jdbc = jdbc; this.named = named; this.tx = tx;
        this.shards = shards; this.leaseSeconds = leaseSeconds; this.maxAttempts = maxAttempts;
    }
    private static final RowMapper<Job> JOB = (r,n) -> new Job(r.getObject("id",UUID.class), r.getObject("tenant_id",UUID.class),
        r.getString("kind"),r.getString("cron"),r.getTimestamp("next_fire_at").toInstant(),r.getString("payload"),
        r.getInt("priority"),r.getInt("max_run_seconds"),r.getString("misfire"),r.getInt("shard"),r.getBoolean("enabled"));
    private static final RowMapper<Run> RUN = (r,n) -> new Run(r.getObject("id",UUID.class),r.getObject("job_id",UUID.class),
        r.getObject("tenant_id",UUID.class),r.getTimestamp("scheduled_at").toInstant(),r.getString("state"),r.getInt("attempt"),
        r.getTimestamp("available_at").toInstant(),r.getTimestamp("lease_until") == null ? null : r.getTimestamp("lease_until").toInstant(),
        r.getString("owner"),r.getLong("fence"),r.getString("idempotency_key"));
    public Instant dbNow() { return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant(); }
    public void heartbeat(String node) { jdbc.update("INSERT INTO worker_node(id,heartbeat_at) VALUES (?,clock_timestamp()) ON CONFLICT(id) DO UPDATE SET heartbeat_at=clock_timestamp()",node); }
    public List<String> activeNodes() { return jdbc.queryForList("SELECT id FROM worker_node WHERE heartbeat_at > clock_timestamp() - interval '5 seconds' ORDER BY id",String.class); }

    public int materialize(Set<Integer> owned) {
        if (owned.isEmpty()) return 0;
        return tx.execute(s -> {
            MapSqlParameterSource p = new MapSqlParameterSource("shards",owned);
            List<Job> jobs = named.query("SELECT j.* FROM job j JOIN tenant t ON t.id=j.tenant_id WHERE j.shard IN (:shards) AND j.enabled AND NOT t.paused AND j.next_fire_at <= clock_timestamp() ORDER BY j.next_fire_at LIMIT 64 FOR UPDATE OF j SKIP LOCKED",p,JOB);
            Instant now = dbNow(); int created = 0;
            for (Job job : jobs) {
                ZoneId zone = tenantZones.get(job.tenantId(),id -> ZoneId.of(jdbc.queryForObject("SELECT zone FROM tenant WHERE id=?",String.class,id)));
                Instant cursor = job.nextFireAt();
                List<Instant> due = new ArrayList<>();
                int limit = 500;
                while (!cursor.isAfter(now) && limit-- > 0) {
                    due.add(cursor);
                    if (job.kind().equals("ONE_OFF")) break;
                    cursor = FireTimes.next(job.cron(),zone,cursor);
                }
                if (job.kind().equals("CRON") && !cursor.isAfter(now) && !job.misfire().equals("FIRE_NOW")) {
                    // A huge outage is advanced in later batches. COALESCE is represented by one pending run.
                    due.clear();
                }
                boolean misfired = job.nextFireAt().isBefore(now.minusSeconds(2));
                List<Instant> fire = misfired ? Misfires.runs(job.misfire(),due,now) : due;
                for (Instant at : fire) {
                    UUID runId = UUID.randomUUID();
                    created += jdbc.update("INSERT INTO job_run(id,job_id,tenant_id,scheduled_at,state,available_at,idempotency_key) VALUES (?,?,?,?,'READY',clock_timestamp(),?) ON CONFLICT(job_id,scheduled_at) DO NOTHING",
                        runId,job.id(),job.tenantId(),Timestamp.from(at),job.id()+":"+at);
                }
                if (job.kind().equals("ONE_OFF")) jdbc.update("UPDATE job SET enabled=false WHERE id=?",job.id());
                else jdbc.update("UPDATE job SET next_fire_at=? WHERE id=?",Timestamp.from(cursor),job.id());
            }
            return created;
        });
    }

    public Optional<Run> claim(String node, Set<Integer> owned) {
        return claimBatch(node,owned,1).stream().findFirst();
    }
    public List<Run> claimBatch(String node, Set<Integer> owned, int limit) {
        if (owned.isEmpty() || limit<=0) return List.of();
        return tx.execute(s -> {
            MapSqlParameterSource p = new MapSqlParameterSource("shards",owned);
            List<Run> candidates = named.query("SELECT r.* FROM job_run r JOIN job j ON j.id=r.job_id JOIN tenant t ON t.id=r.tenant_id WHERE j.shard IN (:shards) AND NOT t.paused AND EXISTS (SELECT 1 FROM tenant_slot ts WHERE ts.tenant_id=r.tenant_id AND (ts.run_id IS NULL OR ts.lease_until<clock_timestamp())) AND ((r.state IN ('READY','RETRY') AND r.available_at <= clock_timestamp()) OR (r.state='RUNNING' AND r.lease_until < clock_timestamp())) AND r.id IN (SELECT DISTINCT ON (r2.tenant_id) r2.id FROM job_run r2 JOIN job j2 ON j2.id=r2.job_id WHERE j2.shard IN (:shards) AND ((r2.state IN ('READY','RETRY') AND r2.available_at <= clock_timestamp()) OR (r2.state='RUNNING' AND r2.lease_until < clock_timestamp())) ORDER BY r2.tenant_id,r2.scheduled_at) ORDER BY r.scheduled_at,j.priority DESC LIMIT 64 FOR UPDATE OF r SKIP LOCKED",p,RUN);
            List<Run> result=new ArrayList<>();
            for (Run candidate : candidates) {
                if(result.size()>=limit) break;
                MapSqlParameterSource perTenant=new MapSqlParameterSource("shards",owned).addValue("tenant",candidate.tenantId()).addValue("take",Math.min(8,limit-result.size()));
                List<Run> tenantRuns=named.query("SELECT r.* FROM job_run r JOIN job j ON j.id=r.job_id WHERE r.tenant_id=:tenant AND j.shard IN (:shards) AND ((r.state IN ('READY','RETRY') AND r.available_at<=clock_timestamp()) OR (r.state='RUNNING' AND r.lease_until<clock_timestamp())) ORDER BY r.scheduled_at,j.priority DESC LIMIT :take FOR UPDATE OF r SKIP LOCKED",perTenant,RUN);
                for(Run run:tenantRuns) {
                if(run.state().equals("RUNNING") && run.attempt()>=maxAttempts) {
                    jdbc.update("UPDATE job_run SET state='DEAD',lease_until=NULL,last_error='Lease expired after maximum attempts' WHERE id=?",run.id());
                    jdbc.update("UPDATE tenant_slot SET run_id=NULL,lease_until=NULL WHERE run_id=?",run.id());
                    continue;
                }
                List<Integer> slots = jdbc.queryForList("SELECT slot FROM tenant_slot WHERE tenant_id=? AND (run_id IS NULL OR lease_until < clock_timestamp()) ORDER BY slot LIMIT 1 FOR UPDATE SKIP LOCKED",Integer.class,run.tenantId());
                if (slots.isEmpty()) continue;
                int slot = slots.getFirst();
                jdbc.update("UPDATE job_run SET state='RUNNING',attempt=attempt+1,owner=?,fence=fence+1,lease_until=clock_timestamp()+(? * interval '1 second'),started_at=clock_timestamp() WHERE id=?",node,leaseSeconds,run.id());
                jdbc.update("UPDATE tenant_slot SET run_id=?,lease_until=clock_timestamp()+(? * interval '1 second') WHERE tenant_id=? AND slot=?",run.id(),leaseSeconds,run.tenantId(),slot);
                result.add(jdbc.queryForObject("SELECT * FROM job_run WHERE id=?",RUN,run.id()));
                }
            }
            return result;
        });
    }

    /** The effect ledger and completion commit atomically, guarded by DB time and the current fence. */
    public boolean commitEffect(Run run, String node) {
        return tx.execute(s -> {
            List<Run> current = jdbc.query("SELECT * FROM job_run WHERE id=? FOR UPDATE",RUN,run.id());
            if (current.isEmpty()) return false;
            Run live = current.getFirst();
            if (live.fence()!=run.fence() || !"RUNNING".equals(live.state()) || !node.equals(live.owner()) || !live.leaseUntil().isAfter(dbNow())) return false;
            Boolean withinRuntime=jdbc.queryForObject("SELECT r.started_at + j.max_run_seconds * interval '1 second' > clock_timestamp() FROM job_run r JOIN job j ON j.id=r.job_id WHERE r.id=?",Boolean.class,run.id());
            if(!Boolean.TRUE.equals(withinRuntime)) return false;
            String payload = jdbc.queryForObject("SELECT payload FROM job WHERE id=?",String.class,run.jobId());
            jdbc.update("INSERT INTO effect(idempotency_key,run_id,tenant_id,payload) VALUES (?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING",run.idempotencyKey(),run.id(),run.tenantId(),payload);
            jdbc.update("UPDATE job_run SET state='SUCCEEDED',completed_at=clock_timestamp(),lease_until=NULL WHERE id=?",run.id());
            jdbc.update("UPDATE tenant_slot SET run_id=NULL,lease_until=NULL WHERE run_id=?",run.id());
            return true;
        });
    }
    public boolean fail(Run run, String node, String reason, Duration delay) {
        return tx.execute(s -> {
            int updated = jdbc.update("UPDATE job_run SET state=CASE WHEN attempt>=? THEN 'DEAD' ELSE 'RETRY' END,available_at=clock_timestamp()+(? * interval '1 millisecond'),last_error=?,lease_until=NULL WHERE id=? AND owner=? AND fence=? AND state='RUNNING' AND lease_until>clock_timestamp()",
                maxAttempts,delay.toMillis(),reason,run.id(),node,run.fence());
            if (updated==1) jdbc.update("UPDATE tenant_slot SET run_id=NULL,lease_until=NULL WHERE run_id=?",run.id());
            return updated==1;
        });
    }
    public boolean renew(Run run,String node) {
        return tx.execute(s -> {
            int updated=jdbc.update("UPDATE job_run r SET lease_until=clock_timestamp()+(? * interval '1 second') FROM job j WHERE r.job_id=j.id AND r.id=? AND r.owner=? AND r.fence=? AND r.state='RUNNING' AND r.lease_until>clock_timestamp() AND r.started_at+j.max_run_seconds*interval '1 second'>clock_timestamp()",leaseSeconds,run.id(),node,run.fence());
            if(updated==1) jdbc.update("UPDATE tenant_slot SET lease_until=clock_timestamp()+(? * interval '1 second') WHERE run_id=?",leaseSeconds,run.id());
            return updated==1;
        });
    }
    public List<Run> dead(UUID tenant) { return jdbc.query("SELECT * FROM job_run WHERE tenant_id=? AND state='DEAD' ORDER BY scheduled_at LIMIT 100",RUN,tenant); }
    public List<Run> recent(UUID tenant) { return jdbc.query("SELECT * FROM job_run WHERE tenant_id=? ORDER BY scheduled_at DESC LIMIT 100",RUN,tenant); }
    public boolean replay(UUID tenant,UUID runId) { return jdbc.update("UPDATE job_run SET state='READY',attempt=0,available_at=clock_timestamp(),last_error=NULL WHERE tenant_id=? AND id=? AND state='DEAD'",tenant,runId)==1; }
    public long count(String state) { return jdbc.queryForObject("SELECT count(*) FROM job_run WHERE state=?",Long.class,state); }
    public long effectCount(UUID runId) { return jdbc.queryForObject("SELECT count(*) FROM effect WHERE run_id=?",Long.class,runId); }
    public Optional<Run> run(UUID id) { return jdbc.query("SELECT * FROM job_run WHERE id=?",RUN,id).stream().findFirst(); }
}
