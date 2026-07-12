package za.co.fnb.dcre.hcs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Primary provider: Nager.Date API adapter (https://date.nager.at), keyless.
 * Non-2xx responses throw; the resilient provider decides whether the fallback
 * takes over or the job fails (level-triggered, next 6h window retries, R-38).
 */
@Component
public class NagerClient implements HolidayProvider {

    private final RestClient restClient;

    public NagerClient(@Value("${dcre.hcs.base-url:https://date.nager.at}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public List<Holiday> fetch(int year, String country) {
        NagerHoliday[] holidays = restClient.get()
                .uri("/api/v3/PublicHolidays/{y}/{c}", year, country)
                .retrieve()
                .body(NagerHoliday[].class);
        return holidays == null ? List.of()
                : Arrays.stream(holidays)
                        .map(h -> new Holiday(LocalDate.parse(h.date()), h.localName(), h.name(), h.global()))
                        .toList();
    }

    /** Subset of the Nager PublicHoliday shape; unknown fields ignored. */
    record NagerHoliday(String date, String localName, String name, boolean global) {
    }
}
