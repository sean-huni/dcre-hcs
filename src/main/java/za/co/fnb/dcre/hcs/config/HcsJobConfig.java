package za.co.fnb.dcre.hcs.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.hcs.service.SyncTasklet;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;

import javax.sql.DataSource;

@Configuration
public class HcsJobConfig {

    @Bean
    public Job hcsJob(JobRepository repo, PlatformTransactionManager tx, SyncTasklet tasklet,
                      HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") String exchangeRoot) {
        Step syncStep = new StepBuilder("syncStep", repo).tasklet(tasklet, tx).build();
        // HCS is DB-only but still emits the business-verdict seam (SCRUM-58 shared listener).
        return new JobBuilder("hcsJob", repo)
                .listener(new OutcomeSeamListener("hcs", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(syncStep)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "HCS_BATCH_", 60);
    }
}
