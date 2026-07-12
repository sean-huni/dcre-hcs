package za.co.fnb.dcre.hcs.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.hcs.data.repo.PublicHolidayRepo;
import za.co.fnb.dcre.hcs.service.NagerClient.NagerHoliday;

import java.time.LocalDate;
import java.util.List;

/**
 * Business tier: syncs the public-holiday calendar (R-38). HCS is the single
 * writer of public_holiday (R-04). Fetches base year + next year per country
 * so CDE's forward roll always has next-January cover; upserts keyed
 * (country, holiday_date) make each 6h window idempotent and self-correcting.
 */
@Service
public class HolidaySyncService {

    private final NagerClient client;
    private final PublicHolidayRepo holidays;

    public HolidaySyncService(NagerClient client, PublicHolidayRepo holidays) {
        this.client = client;
        this.holidays = holidays;
    }

    /** @return number of holidays upserted. */
    public int sync(List<String> countries, int baseYear) {
        int count = 0;
        for (String country : countries) {
            for (int year = baseYear; year <= baseYear + 1; year++) {
                for (NagerHoliday holiday : client.fetch(year, country)) {
                    holidays.upsert(country, LocalDate.parse(holiday.date()),
                            holiday.localName(), holiday.name(), holiday.global());
                    count++;
                }
            }
        }
        return count;
    }
}
