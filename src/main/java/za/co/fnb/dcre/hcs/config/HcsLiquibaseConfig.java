package za.co.fnb.dcre.hcs.config;

import liquibase.UpdateSummaryEnum;
import liquibase.UpdateSummaryOutputEnum;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import za.co.fnb.dcre.hcs.domain.SharedContext;

import javax.sql.DataSource;

/**
 * Declares HCS's Liquibase explicitly so {@link FamilyGuard} can run BEFORE any DDL.
 *
 * <p>Copied from {@code shared/rpt}'s {@code OpsLiquibaseConfig}, including the reason it has to
 * exist at all: Boot 4's {@code LiquibaseAutoConfiguration} backs off entirely once any
 * user-defined {@link SpringLiquibase} bean exists (class-level {@code ConditionalOnMissingBean}
 * on its inner {@code LiquibaseConfiguration}, verified against spring-boot-liquibase 4.1.0), so
 * the migration must be wired here exactly as the auto-configuration would wire it. Every
 * {@code spring.liquibase.*} property that {@code LiquibaseProperties} exposes on Boot 4.1 is
 * honored. NOT reproduced: {@code SpringLiquibaseCustomizer} beans,
 * {@code LiquibaseConnectionDetails}, and the url/user/password/driver-class-name migration
 * DataSource derivation. This bean always migrates through the application DataSource.
 */
@Configuration
@EnableConfigurationProperties(LiquibaseProperties.class)
public class HcsLiquibaseConfig {

    @Bean
    public SpringLiquibase liquibase(final DataSource dataSource, final LiquibaseProperties properties) {
        // Ordering is the whole point: the check runs inside this factory method, so it precedes
        // SpringLiquibase.afterPropertiesSet() by construction rather than by bean-order luck.
        // An HCS pointed at any database but dcre_hcs therefore creates NOTHING before it
        // dies. A guard that ran afterwards would leave the contamination behind.
        FamilyGuard.assertDatabaseMatches(dataSource, SharedContext.HOLIDAYS);
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setClearCheckSums(properties.isClearChecksums());
        if (!CollectionUtils.isEmpty(properties.getContexts())) {
            liquibase.setContexts(StringUtils.collectionToCommaDelimitedString(properties.getContexts()));
        }
        liquibase.setDefaultSchema(properties.getDefaultSchema());
        liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
        liquibase.setLiquibaseTablespace(properties.getLiquibaseTablespace());
        liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
        liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
        liquibase.setDropFirst(properties.isDropFirst());
        liquibase.setShouldRun(properties.isEnabled());
        if (!CollectionUtils.isEmpty(properties.getLabelFilter())) {
            liquibase.setLabelFilter(StringUtils.collectionToCommaDelimitedString(properties.getLabelFilter()));
        }
        liquibase.setChangeLogParameters(properties.getParameters());
        liquibase.setRollbackFile(properties.getRollbackFile());
        liquibase.setTestRollbackOnUpdate(properties.isTestRollbackOnUpdate());
        liquibase.setTag(properties.getTag());
        if (properties.getShowSummary() != null) {
            liquibase.setShowSummary(UpdateSummaryEnum.valueOf(properties.getShowSummary().name()));
        }
        if (properties.getShowSummaryOutput() != null) {
            liquibase.setShowSummaryOutput(UpdateSummaryOutputEnum.valueOf(properties.getShowSummaryOutput().name()));
        }
        if (properties.getUiService() != null) {
            liquibase.setUiService(UIServiceEnum.valueOf(properties.getUiService().name()));
        }
        if (properties.getAnalyticsEnabled() != null) {
            liquibase.setAnalyticsEnabled(properties.getAnalyticsEnabled());
        }
        if (properties.getLicenseKey() != null) {
            liquibase.setLicenseKey(properties.getLicenseKey());
        }
        return liquibase;
    }
}
