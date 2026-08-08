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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        // dcre_hcs must exist before ANY context boots: FamilyGuard compares current_database()
        // against it inside the Liquibase factory method, so a context on the container default
        // would refuse to start (which is exactly what DatabaseBoundaryIT asserts deliberately).
        TestDatabases.create(CRDB, "dcre_hcs");
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

    /** Container URL rewritten to dcre_hcs, the database this service owns. */
    static String hcsDbUrl() {
        return TestDatabases.forDatabase(CRDB, "dcre_hcs");
    }

    static String stubUrl() {
        return "http://localhost:" + NAGER_STUB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", HcsJobTest::hcsDbUrl);
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

    @Test
    void seamFallbackNameIsSelfDescribingWithoutJobNameEnv() throws Exception {
        assumeTrue(System.getenv("JOB_NAME") == null, "requires no JOB_NAME in the test environment");
        JobExecution run = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", "2026-07-12", true)
                .addString("window", "seam", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        Path seam = Path.of("build/test-exchange", "outcomes", "local-hcs-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file " + seam + " (SCRUM-58)");
        assertEquals(List.of("BUSINESS_ACCEPTED"), Files.readAllLines(seam),
                "verdict must stay byte-exact");
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
        registry.add("spring.datasource.url", HcsJobTest::hcsDbUrl);
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
