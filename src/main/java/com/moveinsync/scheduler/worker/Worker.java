package com.moveinsync.scheduler.worker;

import com.moveinsync.scheduler.core.*;
import com.moveinsync.scheduler.db.SchedulerStore;
import com.moveinsync.scheduler.model.Run;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.worker.enabled",havingValue="true",matchIfMissing=true)
public class Worker {
    private static final Logger log=LoggerFactory.getLogger(Worker.class);
    private final SchedulerStore store; private final MeterRegistry metrics;
    private final String id=UUID.randomUUID().toString();
    private final int shards; private final ExecutorService pool=Executors.newFixedThreadPool(16);
    private final ScheduledExecutorService renewer=Executors.newScheduledThreadPool(2);
    private final AtomicInteger inFlight=new AtomicInteger();
    private volatile Set<Integer> owned=Set.of();
    public Worker(SchedulerStore store,MeterRegistry metrics,@Value("${app.shards}") int shards) {
        this.store=store;this.metrics=metrics;this.shards=shards;
        Gauge.builder("scheduler.running",store, s->s.count("RUNNING")).register(metrics);
        Gauge.builder("scheduler.retrying",store,s->s.count("RETRY")).register(metrics);
        Gauge.builder("scheduler.dead",store,s->s.count("DEAD")).register(metrics);
        Gauge.builder("scheduler.queue",store,s->s.count("READY")).register(metrics);
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void membership() {
        store.heartbeat(id);
        owned=ShardAssignment.owned(id,store.activeNodes(),shards);
    }
    @Scheduled(fixedDelayString="${app.poll-ms}",initialDelay=2000)
    public void poll() {
        Set<Integer> mine=owned;
        if(mine.isEmpty()) return;
        store.materialize(mine);
        while(inFlight.get()<16) {
            List<Run> claimed=store.claimBatch(id,mine,16-inFlight.get());
            if(claimed.isEmpty()) break;
            for(Run run:claimed) {
                inFlight.incrementAndGet();
                pool.submit(()->{try { execute(run); } finally {inFlight.decrementAndGet();}});
            }
        }
    }
    private void execute(Run run) {
        double lag=Math.max(0,Duration.between(run.scheduledAt(),store.dbNow()).toMillis()/1000.0);
        io.micrometer.core.instrument.Timer.builder("scheduler.fire.lag").publishPercentileHistogram().register(metrics).record(Duration.ofMillis((long)(lag*1000)));
        ScheduledFuture<?> renewal=renewer.scheduleAtFixedRate(()->{
            try { if(!store.renew(run,id)) metrics.counter("scheduler.fence.rejections").increment(); }
            catch(Exception e) { metrics.counter("scheduler.errors").increment(); }
        },5,5,TimeUnit.SECONDS);
        try {
            if(!store.commitEffect(run,id)) {
                metrics.counter("scheduler.fence.rejections").increment();
                log.atWarn().addKeyValue("runId",run.id()).addKeyValue("fence",run.fence()).log("stale run rejected");
            }
        } catch(Exception e) {
            metrics.counter("scheduler.errors").increment();
            Duration delay=Backoff.delay(run.attempt(),ThreadLocalRandom.current());
            if(store.fail(run,id,"Effect transaction failed",delay)) metrics.counter("scheduler.retries").increment();
            log.atError().addKeyValue("runId",run.id()).addKeyValue("attempt",run.attempt()).log("run failed");
        } finally {
            renewal.cancel(false);
        }
    }
    @PreDestroy public void close() { pool.shutdownNow();renewer.shutdownNow(); }
}
