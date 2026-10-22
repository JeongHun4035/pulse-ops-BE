package com.pulseops;

import com.pulseops.check.*;
import com.pulseops.worker.*;
import com.pulseops.site.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest(properties = {
    "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/pulseops_test}",
    "spring.datasource.username=${TEST_DATABASE_USERNAME:pulseops}",
    "spring.datasource.password=${TEST_DATABASE_PASSWORD:pulseops-local}"})
@AutoConfigureMockMvc
class PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Worker worker;
    @Autowired WorkerStore store;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean UrlPolicy urls;
    @MockitoBean HttpProbe probe;
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("TRUNCATE sites RESTART IDENTITY CASCADE");
        when(urls.validate(anyString())).thenAnswer(a -> new UrlPolicy.Target(URI.create(a.getArgument(0)),
            new InetAddress[]{InetAddress.getByName("8.8.8.8")}));
    }
    private long create(int interval) throws Exception {
        String response = mvc.perform(post("/api/v1/sites").contentType("application/json")
            .content("{\"name\":\"Example\",\"url\":\"https://example.com/\",\"intervalSeconds\":" + interval + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }
    @Test void migrationCrudValidationHistoryAndCascade() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
        long id = create(60);
        mvc.perform(post("/api/v1/sites").contentType("application/json")
            .content("{\"name\":\"dup\",\"url\":\"https://example.com/\",\"intervalSeconds\":300}"))
            .andExpect(status().isConflict());
        for (int interval : new int[]{0, 30, 61, 301, 601}) {
            mvc.perform(post("/api/v1/sites").contentType("application/json")
                .content("{\"name\":\"bad\",\"url\":\"https://other.com\",\"intervalSeconds\":" + interval + "}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/v1/sites/" + id).contentType("application/json")
            .content("{\"name\":\"Updated\",\"intervalSeconds\":300,\"enabled\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"))
            .andExpect(jsonPath("$.enabled").value(false));
        Instant next = jdbc.queryForObject("SELECT next_check_at FROM sites WHERE id=?", java.sql.Timestamp.class, id).toInstant();
        assertThat(next).isAfter(Instant.now().plusSeconds(290));
        mvc.perform(patch("/api/v1/sites/" + id).contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        when(probe.check(anyString())).thenReturn(new HttpProbe.Outcome(Instant.now().minusSeconds(2), "UP", 200, 42L, null));
        worker.runOnce();
        mvc.perform(get("/api/v1/sites")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("UP"));
        mvc.perform(get("/api/v1/sites/" + id + "/history?hours=24"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history[0].responseTimeMs").value(42));
        for (int hours : new int[]{1,6}) mvc.perform(get("/api/v1/sites/" + id + "/history?hours=" + hours)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/sites/" + id + "/history?hours=2")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/sites/" + id)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_results", Integer.class)).isZero();
        mvc.perform(patch("/api/v1/sites/" + id).contentType("application/json").content("{}" )).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sites/" + id + "/history")).andExpect(status().isNotFound());
    }
    @Test void concurrentWorkersOnlyCheckOnceAndDoNotHoldTransactions() throws Exception {
        long id = create(60);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(probe.check(anyString())).thenAnswer(a -> {
            calls.incrementAndGet(); entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new HttpProbe.Outcome(Instant.now(), "UP", 200, 10L, null);
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(worker::runOnce);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> second = executor.submit(worker::runOnce);
                second.get(5, TimeUnit.SECONDS);
                assertThat(calls).hasValue(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'", Integer.class)).isZero();
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_results WHERE site_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_check_at > CURRENT_TIMESTAMP FROM sites WHERE id=?", Boolean.class, id)).isTrue();
        worker.runOnce();
        verify(probe, times(1)).check(anyString());
    }
    @Test void locksReleaseAndDeletedSitesDoNotResurrect() throws Exception {
        long id = create(60);
        try (var claim = store.claim(id, Instant.now())) {
            assertThat(claim).isNotNull(); assertThat(claim.transactionOpen()).isFalse();
            assertThat(store.claim(id, Instant.now())).isNull();
        }
        try (var claim = store.claim(id, Instant.now())) {
            assertThat(claim).isNotNull();
            mvc.perform(delete("/api/v1/sites/" + id)).andExpect(status().isNoContent());
            claim.complete(new HttpProbe.Outcome(Instant.now(), "DOWN", null, null, "timeout"), Instant.now());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_results", Integer.class)).isZero();
    }
    @Test void disabledFutureAndFailureResults() throws Exception {
        long id = create(600);
        jdbc.update("UPDATE sites SET enabled=false WHERE id=?", id); worker.runOnce(); verifyNoInteractions(probe);
        jdbc.update("UPDATE sites SET enabled=true, next_check_at=CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id=?", id);
        worker.runOnce(); verifyNoInteractions(probe);
        jdbc.update("UPDATE sites SET next_check_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?", id);
        when(probe.check(anyString())).thenReturn(new HttpProbe.Outcome(Instant.now(), "DOWN", null, null, "timeout"));
        worker.runOnce();
        mvc.perform(get("/api/v1/sites/" + id + "/history")).andExpect(status().isOk())
            .andExpect(jsonPath("$.history[0].status").value("DOWN"))
            .andExpect(jsonPath("$.history[0].responseTimeMs").isEmpty());
    }
    @Test void apiHasNoScheduledWorkAndExposesSwaggerHealthCors() throws Exception {
        create(60);
        assertThat(context.getBeansOfType(WorkerRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        verifyNoInteractions(probe);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/sites']").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(options("/api/v1/sites").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/sites").header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
    @Test void historyFiltersAndSortsUtcAndIntervalsRemainIndependent() throws Exception {
        long first = create(60);
        mvc.perform(post("/api/v1/sites").contentType("application/json")
            .content("{\"name\":\"Other\",\"url\":\"https://other.example/\",\"intervalSeconds\":600}"))
            .andExpect(status().isCreated());
        when(probe.check(anyString())).thenAnswer(a -> new HttpProbe.Outcome(Instant.now(), "UP", 200, 12L, null));
        worker.runOnce();
        var next = jdbc.queryForList("SELECT interval_seconds, EXTRACT(EPOCH FROM (next_check_at - CURRENT_TIMESTAMP)) AS remaining FROM sites ORDER BY id");
        assertThat(((Number)next.get(0).get("remaining")).doubleValue()).isBetween(50.0, 61.0);
        assertThat(((Number)next.get(1).get("remaining")).doubleValue()).isBetween(590.0, 601.0);
        jdbc.update("INSERT INTO check_results(site_id,checked_at,status,http_status_code,response_time_ms) VALUES (?, CURRENT_TIMESTAMP - INTERVAL '2 hours','DOWN',503,20)", first);
        jdbc.update("INSERT INTO check_results(site_id,checked_at,status,http_status_code,response_time_ms) VALUES (?, CURRENT_TIMESTAMP - INTERVAL '25 hours','UP',200,1)", first);
        mvc.perform(get("/api/v1/sites/" + first + "/history?hours=1"))
            .andExpect(jsonPath("$.history.length()").value(1));
        mvc.perform(get("/api/v1/sites/" + first + "/history?hours=6"))
            .andExpect(jsonPath("$.history.length()").value(2))
            .andExpect(jsonPath("$.history[0].status").value("DOWN"))
            .andExpect(jsonPath("$.history[1].status").value("UP"))
            .andExpect(jsonPath("$.history[0].checkedAt").value(org.hamcrest.Matchers.endsWith("Z")));
    }
    @Test void minuteHistoryFiltersRecentResultsAndRejectsInvalidPeriods() throws Exception {
        long id = create(60);
        for (int age : new int[]{20, 120, 600, 2400, 90000}) {
            jdbc.update("INSERT INTO check_results(site_id,checked_at,status,http_status_code,response_time_ms) VALUES (?, CURRENT_TIMESTAMP - (? * INTERVAL '1 second'),'UP',200,?)",
                id, age, age);
        }
        int[] periods = {1, 5, 30};
        for (int i = 0; i < periods.length; i++) {
            mvc.perform(get("/api/v1/sites/" + id + "/history").param("minutes", String.valueOf(periods[i])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(i + 1));
        }
        mvc.perform(get("/api/v1/sites/" + id + "/history?minutes=30"))
            .andExpect(jsonPath("$.history[0].responseTimeMs").value(600))
            .andExpect(jsonPath("$.history[2].responseTimeMs").value(20));
        mvc.perform(get("/api/v1/sites/" + id + "/history"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(4));
        for (String query : new String[]{"minutes=0", "minutes=-1", "minutes=2", "minutes=60", "minutes=nope", "hours=1&minutes=5"}) {
            mvc.perform(get("/api/v1/sites/" + id + "/history?" + query))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get("/api/v1/sites/999999/history?minutes=1")).andExpect(status().isNotFound());
    }
    @Test void cumulativeHistoryKeepsOldResultsAndAppendsNewWorkerChecks() throws Exception {
        long id = create(60);
        jdbc.update("INSERT INTO check_results(site_id,checked_at,status,http_status_code,response_time_ms) VALUES (?, CURRENT_TIMESTAMP - INTERVAL '2 days','UP',200,111)", id);
        mvc.perform(get("/api/v1/sites/" + id + "/history?minutes=1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(0));
        mvc.perform(get("/api/v1/sites/" + id + "/history?all=true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(1));
        when(probe.check(anyString())).thenAnswer(a -> new HttpProbe.Outcome(Instant.now(), "UP", 200, 222L, null));
        for (int expected : new int[]{2, 3}) {
            jdbc.update("UPDATE sites SET next_check_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?", id);
            worker.runOnce();
            mvc.perform(get("/api/v1/sites/" + id + "/history?all=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(expected))
                .andExpect(jsonPath("$.history[0].responseTimeMs").value(111))
                .andExpect(jsonPath("$.history[" + (expected - 1) + "].responseTimeMs").value(222));
        }
        for (String query : new String[]{"all=true&minutes=1", "all=true&hours=24", "all=invalid"}) {
            mvc.perform(get("/api/v1/sites/" + id + "/history?" + query)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/sites/999999/history?all=true")).andExpect(status().isNotFound());
    }
    @Test void patchDuringFlightPreservesNewScheduleAndInvalidRequestsFail() throws Exception {
        long id = create(60);
        try (var claim = store.claim(id, Instant.now())) {
            assertThat(claim).isNotNull();
            Instant checked = Instant.now();
            mvc.perform(patch("/api/v1/sites/" + id).contentType("application/json").content("{\"intervalSeconds\":600}"))
                .andExpect(status().isOk());
            Instant patched = jdbc.queryForObject("SELECT next_check_at FROM sites WHERE id=?", java.sql.Timestamp.class, id).toInstant();
            claim.complete(new HttpProbe.Outcome(checked, "UP", 200, 1L, null), Instant.now());
            assertThat(jdbc.queryForObject("SELECT next_check_at FROM sites WHERE id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(patched);
        }
        for (String body : new String[]{"{\"intervalSeconds\":30}", "{\"name\":\"   \"}", "{\"url\":\"https://new.example\"}", "{broken"}) {
            mvc.perform(patch("/api/v1/sites/" + id).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get("/api/v1/sites/" + id + "/history?hours=nope")).andExpect(status().isBadRequest());
        when(urls.validate(anyString())).thenThrow(new com.pulseops.web.ApiException(400, "Blocked URL"));
        mvc.perform(post("/api/v1/sites").contentType("application/json")
            .content("{\"name\":\"blocked\",\"url\":\"https://127.0.0.1/\",\"intervalSeconds\":60}"))
            .andExpect(status().isBadRequest());
    }
}
