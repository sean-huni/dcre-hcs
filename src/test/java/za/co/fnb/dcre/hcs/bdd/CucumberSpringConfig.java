package za.co.fnb.dcre.hcs.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import za.co.fnb.dcre.hcs.TestDatabases;
import org.testcontainers.utility.DockerImageName;

/** Same bootstrap as HcsJobTest: batch launch disabled, static CRDB, Nager stubbed. */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
public class CucumberSpringConfig {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        TestDatabases.create(CRDB, "dcre_hcs");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabases.forDatabase(CRDB, "dcre_hcs"));
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.hcs.base-url", NagerStub::url);
    }
}
