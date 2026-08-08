package za.co.fnb.dcre.hcs;

import org.testcontainers.containers.CockroachContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and addresses named databases on the test CockroachDB container.
 *
 * <p>Needed because {@link FamilyGuard} compares {@code current_database()} against
 * {@code dcre_hcs}, and the Testcontainers CockroachDB JDBC URL points at the container's own
 * default database. A suite left on that default would fail every context, and the tempting fix,
 * disabling the guard in tests, would leave the one control this service depends on unexercised.
 * So the tests create the real database name instead.
 *
 * <p>URL rewriting is a single-source copy of {@code shared/rpt}'s {@code RoleConnections}: the
 * container URL carries the database as the path segment and may carry query parameters, which
 * must be preserved.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** Rewrites the container JDBC URL to target another database in the same cluster. */
    public static String forDatabase(final CockroachContainer crdb, final String dbName) {
        final String jdbcUrl = crdb.getJdbcUrl();
        final int queryStart = jdbcUrl.indexOf('?');
        final String base = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
        final String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return "%s/%s%s".formatted(base.substring(0, base.lastIndexOf('/')), dbName, query);
    }

    /** Creates the named databases on the container, from a static block before any context boots. */
    public static void create(final CockroachContainer crdb, final String... names) {
        try (var c = DriverManager.getConnection(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
             Statement s = c.createStatement()) {
            for (final String name : names) {
                s.execute("CREATE DATABASE IF NOT EXISTS " + name);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test databases", e);
        }
    }
}
