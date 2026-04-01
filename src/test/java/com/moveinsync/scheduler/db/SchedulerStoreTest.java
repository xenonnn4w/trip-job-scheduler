package com.moveinsync.scheduler.db;

import static org.junit.jupiter.api.Assertions.*;
import com.moveinsync.scheduler.SchedulerApplication;
import com.moveinsync.scheduler.model.Run;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
@AutoConfigureMockMvc
@SpringBootTest(classes=SchedulerApplication.class,properties="app.worker.enabled=false")
class SchedulerStoreTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("TEST_DB_URL"));
        r.add("spring.datasource.username",()->"scheduler");r.add("spring.datasource.password",()->"scheduler");
    }
    @Autowired SchedulerStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void clean() { jdbc.execute("TRUNCATE tenant CASCADE"); }
    private UUID job(UUID tenant,int cap) {
        jdbc.update("INSERT INTO tenant(id,name,zone,concurrency_cap) VALUES (?,'test','UTC',?)",tenant,cap);
        for(int i=0;i<cap;i++) jdbc.update("INSERT INTO tenant_slot(tenant_id,slot) VALUES (?,?)",tenant,i);
        UUID job=UUID.randomUUID();
        jdbc.update("INSERT INTO job(id,tenant_id,kind,next_fire_at,payload,priority,max_run_seconds,misfire,shard) VALUES (?,?,'ONE_OFF',clock_timestamp()-interval '1 second','alert',0,60,'FIRE_NOW',0)",job,tenant);
        return job;
    }
    @Test void competingNodesClaimOnceAndStaleFenceCannotCommit() throws Exception {
        UUID tenant=UUID.randomUUID();job(tenant,1);store.materialize(Set.of(0));
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Run>> a=threads.submit(()->store.claim("a",Set.of(0)));
            Future<Optional<Run>> b=threads.submit(()->store.claim("b",Set.of(0)));
            List<Run> claimed=new ArrayList<>();a.get().ifPresent(claimed::add);b.get().ifPresent(claimed::add);
            assertEquals(1,claimed.size());Run first=claimed.getFirst();
            jdbc.update("UPDATE job_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.id());
            jdbc.update("UPDATE tenant_slot SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?",first.id());
            Run second=store.claim("b",Set.of(0)).orElseThrow();
            assertTrue(second.fence()>first.fence());
            assertFalse(store.commitEffect(first,first.owner()));
            assertTrue(store.commitEffect(second,"b"));
            assertEquals(1,store.effectCount(second.id()));
            assertFalse(store.commitEffect(second,"b"));
        } finally {threads.shutdownNow();}
    }
    @Test void tenantCapAndRetryDeadLetter() {
        UUID tenant=UUID.randomUUID();UUID job=job(tenant,1);store.materialize(Set.of(0));
        for(int i=0;i<2;i++) jdbc.update("INSERT INTO job_run(id,job_id,tenant_id,scheduled_at,state,available_at,idempotency_key) VALUES (?,?,?,clock_timestamp()+(? * interval '1 second'),'READY',clock_timestamp(),?)",UUID.randomUUID(),job,tenant,i+1,"extra:"+UUID.randomUUID());
        Run first=store.claim("a",Set.of(0)).orElseThrow();
        assertTrue(store.claim("b",Set.of(0)).isEmpty());
        for(int i=0;i<5;i++) {
            assertTrue(store.fail(first,"a","injected",Duration.ZERO));
            if(i<4) first=store.claim("a",Set.of(0)).orElseThrow();
        }
        assertEquals("DEAD",store.run(first.id()).orElseThrow().state());
        assertTrue(store.replay(tenant,first.id()));
    }
    @Test void employeeCannotReadAnotherTenant() throws Exception {
        UUID own=UUID.randomUUID(),other=UUID.randomUUID();
        UUID ownJob=job(own,1),otherJob=job(other,1);
        String username="employee-"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role,tenant_id) VALUES (?,?,'EMPLOYEE',?)",username,encoder.encode("long-test-password"),own);
        mvc.perform(get("/api/tenants/{tenant}/jobs/{job}/next",own,ownJob).with(httpBasic(username,"long-test-password")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{tenant}/jobs/{job}/next",other,otherJob).with(httpBasic(username,"long-test-password")))
            .andExpect(status().isNotFound());
    }
    @Test void dashboardListsOnlyAuthenticatedTenantData() throws Exception {
        UUID own=UUID.randomUUID(),other=UUID.randomUUID();
        job(own,1);job(other,1);
        String username="dashboard-"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role,tenant_id) VALUES (?,?,'EMPLOYEE',?)",username,encoder.encode("long-test-password"),own);
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tenants").with(httpBasic(username,"long-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(own.toString()));
        mvc.perform(get("/api/tenants/{tenant}/overview",own).with(httpBasic(username,"long-test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jobs").value(1));
        mvc.perform(get("/api/tenants/{tenant}/jobs",other).with(httpBasic(username,"long-test-password")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/tenants/{tenant}/runs",other).with(httpBasic(username,"long-test-password")))
            .andExpect(status().isNotFound());
    }
}
