package za.co.fnb.dcre.hcs.config;

import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.hcs.domain.SharedContext;

import javax.sql.DataSource;

/**
 * Refuses to migrate when the owned context and the connected database disagree.
 *
 * <p>Copied from {@code shared/rpt}'s guard of the same name rather than reinvented, so the fleet
 * has ONE mechanism for this. The only difference is what it compares against: rpt guards a
 * family's database, this guards a single-service shared context's database.
 *
 * <p><b>What it prevents here.</b> {@code public_holiday} used to live in {@code dcre_col}, the
 * collections family's database, read by services in both families. Pointing {@code DCRE_DB_URL}
 * back at {@code dcre_col} is a one-variable typo that would read as a working deployment: HCS
 * would recreate the holiday calendar inside another context's database and quietly restore the
 * exact contamination the split removes. The owner ruled this out in as many words on 2026-08-08,
 * calling it a 12FactorApp violation (https://12factor.net/).
 *
 * <p><b>It refuses every other database, not merely the family ones.</b> An equality check against
 * the owned database rejects everything else by construction, which is strictly stronger than a
 * denylist and cannot rot as databases are added or removed. That property is what let
 * {@code shared/acs} be retired on 2026-08-09 without touching a line of this class: {@code hcs}
 * pointed at the retired {@code dcre_acs} still dies exactly as it does when pointed at
 * {@code dcre_col}, and both cases are asserted separately in {@code DatabaseBoundaryIT}. The
 * refusal never depended on the sibling being enumerated anywhere, which is the whole reason a
 * denylist was not used: "reference data" is a category rather than a bounded context, and a guard
 * that only knew the databases somebody remembered to list would let the next shared store merge
 * back in, which is the shared-database mistake at smaller scale.
 *
 * <p>It fails CLOSED and on the SPECIFIC mismatch, never on "something looked wrong": the message
 * names both sides, so a wrong answer can never be mistaken for an unavailable one.
 */
public final class FamilyGuard {

    private FamilyGuard() {
    }

    /**
     * @throws IllegalStateException when {@code current_database()} is not the context's own
     *                               database. Thrown before any changelog runs, so a mismatched
     *                               process creates nothing at all.
     */
    public static void assertDatabaseMatches(final DataSource dataSource, final SharedContext context) {
        final String actual = new JdbcTemplate(dataSource)
                .queryForObject("SELECT current_database()", String.class);
        if (!context.database().equals(actual)) {
            throw new IllegalStateException(
                    "dcre service '%s' owns database '%s' but this datasource is connected to '%s'; "
                            .formatted(context.service(), context.database(), actual)
                            + "refusing to create the " + context.service()
                            + " reference tables in another context's database");
        }
    }
}
