package za.co.fnb.dcre.hcs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.hcs.config.FamilyGuard;
import za.co.fnb.dcre.hcs.domain.SharedContext;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared-reference boundary, asserted rather than described.
 *
 * <p>Booting this context is itself the happy-path control: {@link FamilyGuard} runs inside the
 * Liquibase bean factory method, so a context that refreshes at all has proved that HCS migrated
 * {@code dcre_hcs} and nothing else. The tests below add the negative arms, each asserting exactly
 * one control so losing any of them cannot be masked by the others.
 *
 * <p>The two refusals are separate tests on purpose. Refusing {@code dcre_col} is the owner's
 * 2026-08-08 directive. Refusing {@code dcre_acs} is the RETIRED-NAME arm: {@code shared/acs} and
 * that database were retired on 2026-08-09 in favour of one immutable versioned artifact each
 * context materialises locally, and the design they replace was built the day before, so it is one
 * revert away. The refusal costs one string and can only ever do one thing, which is fail the
 * build the moment somebody points HCS at a resurrected shared reference store. A single test
 * covering "some other database" would pass while either half rotted.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class DatabaseBoundaryIT {

    /** Named, never "some other database": a wrong pick here would weaken the assertion silently. */
    private static final String COLLECTIONS_DB = "dcre_col";
    private static final String RETIRED_SHARED_DB = "dcre_acs";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", HcsJobTest::hcsDbUrl);
        registry.add("spring.datasource.username", HcsJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", HcsJobTest.CRDB::getPassword);
        registry.add("dcre.hcs.base-url", HcsJobTest::stubUrl);
    }

    @Test
    void guardRefusesTheCollectionsDatabaseAndCreatesNothingInIt() throws SQLException {
        assertRefusesAndLeavesNoTrace(COLLECTIONS_DB);
    }

    @Test
    void guardRefusesTheRetiredSharedDatabaseAndCreatesNothingInIt() throws SQLException {
        assertRefusesAndLeavesNoTrace(RETIRED_SHARED_DB);
    }

    /**
     * Refusal is only half the property. The other half is that the refusal happens BEFORE any
     * DDL, which is what makes the guard a boundary rather than a warning, so the absence of
     * {@code public_holiday} in the foreign database is asserted too.
     *
     * <p>That absence is checked with a POSITIVE CONTROL through the identical query. An absence
     * found by searching proves nothing until the instrument is shown able to find something: if
     * the relation lookup cannot see a table that certainly exists, it could never have seen
     * {@code public_holiday} either, and a broken probe would read as a clean result.
     */
    private void assertRefusesAndLeavesNoTrace(final String foreignDb) throws SQLException {
        createDatabase(foreignDb);
        seedControlRelation(foreignDb);

        final var foreign = new DriverManagerDataSource(
                TestDatabases.forDatabase(HcsJobTest.CRDB, foreignDb),
                HcsJobTest.CRDB.getUsername(), HcsJobTest.CRDB.getPassword());

        final var thrown = assertThrows(IllegalStateException.class,
                () -> FamilyGuard.assertDatabaseMatches(foreign, SharedContext.HOLIDAYS));

        // Assert the SPECIFIC failure. "It threw" would also pass with a missing driver, a dead
        // container or a malformed query, none of which is the boundary under test.
        assertTrue(thrown.getMessage().contains("'hcs'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'dcre_hcs'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'" + foreignDb + "'"), thrown.getMessage());

        assertTrue(relationExists(foreignDb, "boundary_probe_control"),
                "INSTRUMENT FAILURE: the relation lookup cannot see a table that was just created "
                        + "in " + foreignDb + ", so its report of no public_holiday carries no "
                        + "information at all");
        assertEquals(false, relationExists(foreignDb, "public_holiday"),
                "hcs created public_holiday in " + foreignDb + "; the guard refused too late to be "
                        + "a boundary");
    }

    /**
     * The property the owner actually asked for: HCS pointed at {@code dcre_col} REFUSES TO START.
     *
     * <p>The tests above call the guard directly, which proves the comparison but not the wiring.
     * This one boots a real application context and asserts the refusal reaches startup, which is
     * the only form of the claim an operator can rely on. Booting the whole application is also
     * what proves the guard sits AHEAD of Liquibase: if it were ordered after, the context would
     * still fail, but {@code public_holiday} would exist in dcre_col afterwards, which is asserted.
     */
    @Test
    void applicationRefusesToStartAgainstTheCollectionsDatabaseAndMigratesNothing() throws SQLException {
        createDatabase(COLLECTIONS_DB);
        seedControlRelation(COLLECTIONS_DB);

        // Command-line args, NOT builder.properties(). properties() populates defaultProperties,
        // which is the LOWEST-precedence source and loses to application.yml, so the app silently
        // dialled the committed localhost dev default instead of the container and died with
        // 'database "dcre_hcs" does not exist'. That failure looks enough like a refusal to be
        // mistaken for one, which is why this asserts the guard's own wording rather than merely
        // that startup failed.
        final var thrown = assertThrows(Exception.class, () -> new SpringApplicationBuilder(HcsApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + TestDatabases.forDatabase(HcsJobTest.CRDB, COLLECTIONS_DB),
                        "--spring.datasource.username=" + HcsJobTest.CRDB.getUsername(),
                        "--spring.datasource.password=" + HcsJobTest.CRDB.getPassword(),
                        "--spring.batch.job.enabled=false",
                        "--dcre.exchange-root=build/test-exchange",
                        "--dcre.hcs.base-url=" + HcsJobTest.stubUrl())
                .close());

        assertTrue(rootCauseMessage(thrown).contains("refusing to create the hcs reference tables"),
                rootCauseMessage(thrown));

        assertTrue(relationExists(COLLECTIONS_DB, "boundary_probe_control"),
                "INSTRUMENT FAILURE: the relation lookup cannot see a table that certainly exists "
                        + "in " + COLLECTIONS_DB);
        assertEquals(false, relationExists(COLLECTIONS_DB, "public_holiday"),
                "the application created public_holiday in " + COLLECTIONS_DB + " before dying; the "
                        + "guard is ordered after Liquibase and is therefore not a boundary");
        assertEquals(false, relationExists(COLLECTIONS_DB, "hcs_databasechangelog"),
                "the application wrote its Liquibase history into " + COLLECTIONS_DB);
    }

    private static String rootCauseMessage(final Throwable thrown) {
        Throwable t = thrown;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? thrown.toString() : t.getMessage();
    }

    @Test
    void theSharedContextResolvesToItsOwnDatabaseAndATypoResolvesToNothing() {
        assertEquals("dcre_hcs", SharedContext.fromService("hcs").database());
        // No silent default. A typo must not land on a context nobody chose.
        assertThrows(IllegalArgumentException.class, () -> SharedContext.fromService("hc"));
        assertThrows(IllegalArgumentException.class, () -> SharedContext.fromService(""));
    }

    /**
     * HCS is the ONLY shared reference context, asserted as a count.
     *
     * <p>Separate from the resolution test below on purpose: a test that passes for several
     * reasons cannot detect the loss of any one of them. This one bites when an entry is ADDED
     * whatever it is called; the next one bites when the RETIRED name in particular comes back.
     * Losing either would leave the other looking like coverage.
     */
    @Test
    void hcsIsTheOnlySharedReferenceContext() {
        assertEquals(1, SharedContext.values().length,
                "a second shared reference context must be a deliberate decision, not an"
                        + " inherited one; the last one, acs, was retired on 2026-08-09 for"
                        + " having no authoritative source, owner, ingestion or freshness"
                        + " contract. Got " + java.util.Arrays.toString(SharedContext.values()));
    }

    /**
     * The retired name in particular does not resolve.
     *
     * <p>Asserted to THROW rather than merely omitted from the happy cases. An omission is not a
     * control: re-adding an {@code ACCOUNTS} entry would make {@code fromService("acs")} resolve
     * again, silently, and the database it names is one infra no longer creates.
     */
    @Test
    void theRetiredAccountContextDoesNotResolve() {
        assertThrows(IllegalArgumentException.class, () -> SharedContext.fromService("acs"),
                "acs was retired on 2026-08-09; resolving it would hand back a database that"
                        + " exists in no environment");
    }

    /** The happy path, stated rather than implied by the context having refreshed. */
    @Test
    void hcsOwnDatabaseCarriesTheTableAndThePublishedView() throws SQLException {
        assertTrue(relationExists("dcre_hcs", "public_holiday"));
        assertTrue(relationExists("dcre_hcs", "hol_cde_view"));
    }

    private boolean relationExists(final String db, final String relation) throws SQLException {
        try (var c = DriverManager.getConnection(TestDatabases.forDatabase(HcsJobTest.CRDB, db),
                HcsJobTest.CRDB.getUsername(), HcsJobTest.CRDB.getPassword());
             Statement s = c.createStatement()) {
            final ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                            + relation + "'");
            assertTrue(rs.next());
            return rs.getInt(1) > 0;
        }
    }

    /**
     * A relation that certainly exists in the foreign database, so the absence assertion above has
     * something to prove itself against.
     */
    private void seedControlRelation(final String db) throws SQLException {
        try (var c = DriverManager.getConnection(TestDatabases.forDatabase(HcsJobTest.CRDB, db),
                HcsJobTest.CRDB.getUsername(), HcsJobTest.CRDB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS boundary_probe_control (id INT PRIMARY KEY)");
        }
    }

    private static void createDatabase(final String name) throws SQLException {
        assertNotNull(name);
        TestDatabases.create(HcsJobTest.CRDB, name);
    }
}
