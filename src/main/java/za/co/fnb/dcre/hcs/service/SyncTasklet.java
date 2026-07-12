package za.co.fnb.dcre.hcs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class SyncTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(SyncTasklet.class);

    private final HolidaySyncService service;

    public SyncTasklet(HolidaySyncService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var params = chunkContext.getStepContext().getJobParameters();
        LocalDate syncDate = LocalDate.parse((String) params.get("sync.date"));
        String countries = (String) params.getOrDefault("countries", "ZA");
        int synced = service.sync(List.of(countries.split(",")), syncDate.getYear());
        log.info("synced {} public holidays for countries={} baseYear={}",
                synced, countries, syncDate.getYear());
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("synced", synced);
        return RepeatStatus.FINISHED;
    }
}
