package za.co.fnb.dcre.hcs.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The cross-family shared reference contexts, each with the one database it owns.
 *
 * <p>Deliberately mirrors {@code rpt}'s {@code Family} enum rather than inventing a second shape:
 * the mapping is domain fact, not configuration. A database that can be pointed anywhere by a
 * setting is not a boundary, it is a default.
 *
 * <p><b>Why this is not a family.</b> The family databases are named for a family because nine
 * or ten services share each one, so the family IS the context. A single-service context has no
 * such distinction, so the database takes the SERVICE's name: {@code hcs} owns {@code dcre_hcs}.
 *
 * <p><b>There is exactly ONE entry, and the guard is not weaker for it.</b> An {@code ACCOUNTS}
 * entry naming {@code acs} / {@code dcre_acs} sat here until 2026-08-09, on the argument that
 * naming the sibling is what lets {@code FamilyGuard} refuse it BY NAME. That argument was wrong
 * about the mechanism: the guard is an EQUALITY check against the owned database, so it rejects
 * every other database by construction, which is strictly stronger than a denylist and cannot rot
 * as databases are added or removed. HCS pointed at {@code dcre_acs} still dies exactly as it does
 * when pointed at {@code dcre_col}, which is the owner's 2026-08-08 directive and is asserted in
 * {@code DatabaseBoundaryIT}. {@code shared/acs} and {@code dcre_acs} were then retired in favour
 * of one immutable versioned artifact each context materialises locally, so a second entry here
 * would name a database that exists nowhere, and {@link #fromService} would resolve {@code "acs"}
 * to it instead of failing loudly.
 *
 * @see <a href="https://microservices.io/patterns/data/database-per-service.html">Database per Service</a>
 */
public enum SharedContext {

    /** The holiday calendar. {@code hcs} is its only writer (R-04). */
    HOLIDAYS("hcs", "dcre_hcs");

    private final String service;
    private final String database;

    SharedContext(final String service, final String database) {
        this.service = service;
        this.database = database;
    }

    /** The database this context owns, and the only one its changelog may be applied to. */
    public String database() {
        return database;
    }

    /** The owning service's token, which is also its Liquibase history-table name segment. */
    public String service() {
        return service;
    }

    /** Fails loudly on an unknown token rather than defaulting to a context nobody chose. */
    public static SharedContext fromService(final String token) {
        return Arrays.stream(values())
                .filter(c -> c.service().equalsIgnoreCase(token))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown shared context '%s'; expected one of %s"
                                .formatted(token, Arrays.stream(values()).map(SharedContext::service).toList())));
    }
}
