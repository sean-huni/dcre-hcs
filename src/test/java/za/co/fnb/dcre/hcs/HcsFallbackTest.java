package za.co.fnb.dcre.hcs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fallback path (R-38): primary Nager stub answers 500 for every call, a second
 * JDK HttpServer serves the Calendarific-compatible shape, and the API key is set.
 * The sync must succeed via the fallback, and once the circuit breaker opens
 * (minimumNumberOfCalls=2, 100% failure rate) the primary must stop receiving
 * calls for the remaining country/year fetches of the run.
 *
 * Uses countries US,GB only: ZA rows belong to HcsJobTest's assertions and the
 * CockroachDB container is shared across test contexts.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class HcsFallbackTest {

    static final AtomicInteger PRIMARY_CALLS = new AtomicInteger();

    /** Primary stub: unconditional 500, counting every request it receives. */
    static final HttpServer PRIMARY_STUB;

    /** Fallback stub: Calendarific-compatible /api/v2/holidays responses. */
    static final HttpServer FALLBACK_STUB;

    static {
        try {
            PRIMARY_STUB = HttpServer.create(new InetSocketAddress(0), 0);
            FALLBACK_STUB = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PRIMARY_STUB.createContext("/", ex -> {
            PRIMARY_CALLS.incrementAndGet();
            respond(ex, 500, "boom");
        });
        FALLBACK_STUB.createContext("/api/v2/holidays", HcsFallbackTest::respondCalendarific);
        PRIMARY_STUB.start();
        FALLBACK_STUB.start();
    }

    static void respondCalendarific(HttpExchange exchange) throws IOException {
        Map<String, String> query = Arrays.stream(exchange.getRequestURI().getQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
        if (!"test-key".equals(query.get("api_key"))) {
            respond(exchange, 401, "{\"meta\":{\"code\":401}}");
            return;
        }
        String year = query.get("year");
        String body = """
                {"response":{"holidays":[
                  {"name":"New Year's Day","description":"First day of the year",
                   "date":{"iso":"%s-01-01"},"type":["National holiday"]},
                  {"name":"Some Observance","description":"Not a day off",
                   "date":{"iso":"%s-03-02"},"type":["Observance"]}]}}""".formatted(year, year);
        respond(exchange, 200, body);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String primaryUrl() {
        return "http://localhost:" + PRIMARY_STUB.getAddress().getPort();
    }

    static String fallbackUrl() {
        return "http://localhost:" + FALLBACK_STUB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", HcsJobTest::hcsDbUrl);
        registry.add("spring.datasource.username", HcsJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", HcsJobTest.CRDB::getPassword);
        registry.add("dcre.hcs.base-url", HcsFallbackTest::primaryUrl);
        registry.add("dcre.hcs.fallback.base-url", HcsFallbackTest::fallbackUrl);
        registry.add("dcre.hcs.fallback.api-key", () -> "test-key");
    }

    @Autowired
    Job hcsJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fallsBackWhenPrimaryIsDownAndBreakerSkipsDeadPrimary() throws Exception {
        PRIMARY_CALLS.set(0);
        JobExecution run = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "wfb1", true)
                .addString("countries", "US,GB", false).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "sync must succeed via the fallback API when the primary is down");

        // 2 countries x 2 years x 2 holidays, keyed (country, holiday_date)
        assertEquals(8, jdbc.queryForObject(
                "SELECT count(*) FROM public_holiday WHERE country IN ('US','GB')", Integer.class),
                "fallback holidays must be upserted into public_holiday");
        assertTrue(jdbc.queryForObject(
                "SELECT is_global FROM public_holiday WHERE country='US' AND holiday_date='2026-01-01'",
                Boolean.class),
                "type containing 'National holiday' maps to is_global=true");
        assertFalse(jdbc.queryForObject(
                "SELECT is_global FROM public_holiday WHERE country='US' AND holiday_date='2026-03-02'",
                Boolean.class),
                "other Calendarific types map to is_global=false");
        assertEquals("New Year's Day", jdbc.queryForObject(
                "SELECT local_name FROM public_holiday WHERE country='GB' AND holiday_date='2027-01-01'",
                String.class),
                "Calendarific name maps to both name and local_name");

        // 4 fetches (US,GB x 2026,2027); the breaker opens after 2 failed primary
        // calls (minimumNumberOfCalls=2, 100% failure), so the remaining 2 fetches
        // must not reach the primary: its request count stops growing at 2.
        assertEquals(2, PRIMARY_CALLS.get(),
                "circuit breaker must stop routing calls to the dead primary after the threshold");
    }
}

/**
 * Separate context: primary down AND no fallback API key (default empty), so the
 * fallback is disabled and the failure must propagate: the job fails and the next
 * 6h window retries (level-triggered, R-38).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class HcsFallbackDisabledTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", HcsJobTest::hcsDbUrl);
        registry.add("spring.datasource.username", HcsJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", HcsJobTest.CRDB::getPassword);
        registry.add("dcre.hcs.base-url", HcsFallbackTest::primaryUrl);
        registry.add("dcre.hcs.fallback.base-url", HcsFallbackTest::fallbackUrl);
        // dcre.hcs.fallback.api-key intentionally left at its empty default
    }

    @Autowired
    Job hcsJob;

    @Autowired
    JobOperator jobOperator;

    @Test
    void failsJobWhenPrimaryDownAndFallbackDisabled() throws Exception {
        JobExecution run = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "wfb2", true)
                .addString("countries", "US", false).toJobParameters());
        assertEquals(BatchStatus.FAILED, run.getStatus(),
                "with the fallback disabled the primary failure must fail the job (R-38 next-window retry)");
    }
}
