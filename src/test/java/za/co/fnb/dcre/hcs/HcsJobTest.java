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
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class HcsJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final String JSON_2026 = """
            [{"date":"2026-01-01","localName":"Nuwejaarsdag","name":"New Year's Day","countryCode":"ZA","global":true},
             {"date":"2026-12-25","localName":"Kersfees","name":"Christmas Day","countryCode":"ZA","global":true}]""";

    static final String JSON_2027 = """
            [{"date":"2027-01-01","localName":"Nuwejaarsdag","name":"New Year's Day","countryCode":"ZA","global":true}]""";

    /** Nager.Date stub: fixture JSON under /api, unconditional 500 under /fail. */
    static final HttpServer NAGER_STUB;

    static {
        CRDB.start();
        try {
            NAGER_STUB = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NAGER_STUB.createContext("/api/v3/PublicHolidays/2026/ZA", ex -> respond(ex, 200, JSON_2026));
        NAGER_STUB.createContext("/api/v3/PublicHolidays/2027/ZA", ex -> respond(ex, 200, JSON_2027));
        NAGER_STUB.createContext("/fail", ex -> respond(ex, 500, "boom"));
        NAGER_STUB.start();
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String stubUrl() {
        return "http://localhost:" + NAGER_STUB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.hcs.base-url", HcsJobTest::stubUrl);
    }

    @Autowired
    Job hcsJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void syncsCurrentAndNextYearIdempotently() throws Exception {
        JobExecution run = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "w1", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM public_holiday WHERE country='ZA'", Integer.class));
        assertEquals("Christmas Day", jdbc.queryForObject(
                "SELECT name FROM public_holiday WHERE country='ZA' AND holiday_date='2026-12-25'", String.class));

        JobExecution rerun = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "w2", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM public_holiday WHERE country='ZA'", Integer.class),
                "6h window resync is an upsert no-op");
    }
}

/**
 * Separate context: base-url points at the stub's /fail handler (plain 500),
 * proving non-2xx from Nager fails the job (level-triggered, R-38).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class HcsJobFailureTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", HcsJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", HcsJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", HcsJobTest.CRDB::getPassword);
        registry.add("dcre.hcs.base-url", () -> HcsJobTest.stubUrl() + "/fail");
    }

    @Autowired
    Job hcsJob;

    @Autowired
    JobOperator jobOperator;

    @Test
    void failsJobWhenNagerReturns500() throws Exception {
        JobExecution run = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "wf", true).toJobParameters());
        assertEquals(BatchStatus.FAILED, run.getStatus());
    }
}
