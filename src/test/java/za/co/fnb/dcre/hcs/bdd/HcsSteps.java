package za.co.fnb.dcre.hcs.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.sql.Date;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Glue for the holiday sync feature; scenario-scoped (fresh instance per scenario). */
public class HcsSteps {

    @Autowired
    Job hcsJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CircuitBreaker nagerCircuitBreaker;

    private JobExecution lastRun;

    @Before
    public void resetUpstream() {
        NagerStub.reset();
        // Per-run breaker state in prod (ephemeral JVM); reset keeps scenarios order-independent.
        nagerCircuitBreaker.reset();
    }

    @Given("the Nager API serves the standard ZA fixtures")
    public void standardFixtures() {
        NagerStub.reset();
    }

    @Given("a completed holiday sync for date {string}")
    public void completedSync(String date) throws Exception {
        runSync(date);
        assertEquals(BatchStatus.COMPLETED, lastRun.getStatus(), "the priming sync must complete");
    }

    @Given("the Nager API now serves local name {string} for {string}")
    public void nagerServesLocalName(String localName, String date) {
        NagerStub.overrideLocalName(date, localName);
    }

    @Given("the Nager API starts returning 500")
    public void nagerStartsFailing() {
        NagerStub.failUpstream();
    }

    @When("the holiday sync runs for date {string}")
    public void syncRuns(String date) throws Exception {
        runSync(date);
    }

    @Then("the HCS job completes")
    public void jobCompletes() {
        assertEquals(BatchStatus.COMPLETED, lastRun.getStatus());
    }

    @Then("the HCS job fails")
    public void jobFails() {
        assertEquals(BatchStatus.FAILED, lastRun.getStatus(), "non-2xx upstream must fail the job (R-38)");
    }

    @Then("{int} ZA public holidays are stored")
    public void zaHolidayCount(int expected) {
        assertEquals(expected, (int) jdbc.queryForObject(
                "SELECT count(*) FROM public_holiday WHERE country='ZA'", Integer.class));
    }

    @Then("the ZA holiday on {string} is named {string}")
    public void zaHolidayNamed(String date, String name) {
        assertEquals(name, jdbc.queryForObject(
                "SELECT name FROM public_holiday WHERE country='ZA' AND holiday_date=?",
                String.class, Date.valueOf(date)));
    }

    @Then("the ZA holiday on {string} is stored")
    public void zaHolidayStored(String date) {
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM public_holiday WHERE country='ZA' AND holiday_date=?",
                Integer.class, Date.valueOf(date)));
    }

    @Then("the ZA holiday on {string} has local name {string}")
    public void zaHolidayLocalName(String date, String localName) {
        assertEquals(localName, jdbc.queryForObject(
                "SELECT local_name FROM public_holiday WHERE country='ZA' AND holiday_date=?",
                String.class, Date.valueOf(date)));
    }

    private void runSync(String date) throws Exception {
        lastRun = jobOperator.start(hcsJob, new JobParametersBuilder()
                .addString("sync.date", date, true)
                .addString("window", UUID.randomUUID().toString(), true)
                .toJobParameters());
    }
}
