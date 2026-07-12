package za.co.fnb.dcre.hcs.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The provider HolidaySyncService uses: primary (Nager) behind a circuit breaker,
 * with an optional key-gated Calendarific-compatible fallback.
 *
 * The HCS JVM is ephemeral (one Kubernetes Job per 6h window), so circuit breaker
 * state is per-run: its only value is skipping the dead primary for the remaining
 * country/year fetches within one run, going straight to the fallback instead of
 * waiting out repeated primary failures.
 *
 * When the fallback is disabled (no API key) or itself fails, the exception is
 * rethrown so the job still fails and the next 6h window retries (R-38 semantics).
 */
@Component
public class ResilientHolidayProvider implements HolidayProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientHolidayProvider.class);

    private final NagerClient primary;
    private final FallbackHolidayClient fallback;
    private final CircuitBreaker circuitBreaker;

    public ResilientHolidayProvider(NagerClient primary, FallbackHolidayClient fallback,
                                    CircuitBreaker circuitBreaker) {
        this.primary = primary;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public List<Holiday> fetch(int year, String country) {
        try {
            // Open breaker throws CallNotPermittedException, also handled below
            return circuitBreaker.executeSupplier(() -> primary.fetch(year, country));
        } catch (RuntimeException primaryFailure) {
            if (!fallback.enabled()) {
                throw primaryFailure;
            }
            log.warn("primary holiday API failed for {}/{} ({}), trying fallback",
                    country, year, primaryFailure.getMessage());
            try {
                return fallback.fetch(year, country);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }
}
