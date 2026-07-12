package za.co.fnb.dcre.hcs.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.hcs.data.repo.PublicHolidayRepo;
import za.co.fnb.dcre.hcs.service.HolidayProvider.Holiday;

import java.util.List;

/**
 * Business tier: syncs the public-holiday calendar (R-38). HCS is the single
 * writer of public_holiday (R-04). Fetches base year + next year per country
 * so CDE's forward roll always has next-January cover; upserts keyed
 * (country, holiday_date) make each 6h window idempotent and self-correcting.
 * Holidays come from the resilient provider: Nager primary behind a circuit
 * breaker, optional key-gated fallback.
 */
@Service
public class HolidaySyncService {

    private final ResilientHolidayProvider provider;
    private final PublicHolidayRepo holidays;

    public HolidaySyncService(ResilientHolidayProvider provider, PublicHolidayRepo holidays) {
        this.provider = provider;
        this.holidays = holidays;
    }

    /** @return number of holidays upserted. */
    public int sync(List<String> countries, int baseYear) {
        int count = 0;
        for (String country : countries) {
            for (int year = baseYear; year <= baseYear + 1; year++) {
                for (Holiday holiday : provider.fetch(year, country)) {
                    holidays.upsert(country, holiday.date(),
                            holiday.localName(), holiday.name(), holiday.global());
                    count++;
                }
            }
        }
        return count;
    }
}
