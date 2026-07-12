package za.co.fnb.dcre.hcs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Fallback provider: Calendarific-compatible API (https://calendarific.com),
 * GET /api/v2/holidays?api_key=...&country=...&year=...
 *
 * Key-gated: an empty api-key (the default) means the fallback is disabled and
 * fetch() throws, preserving the fail-and-retry semantics of a keyless clone.
 *
 * Field mapping (Calendarific has no localName concept): holiday name maps to
 * both name and local_name; is_global is true when the type array contains
 * "National holiday", false for every other type (Observance, Season, etc.).
 */
@Component
public class FallbackHolidayClient implements HolidayProvider {

    static final String NATIONAL_HOLIDAY_TYPE = "National holiday";

    private final RestClient restClient;
    private final String apiKey;

    public FallbackHolidayClient(
            @Value("${dcre.hcs.fallback.base-url:https://calendarific.com}") String baseUrl,
            @Value("${dcre.hcs.fallback.api-key:}") String apiKey) {
        this.restClient = RestClient.create(baseUrl);
        this.apiKey = apiKey;
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }

    @Override
    public List<Holiday> fetch(int year, String country) {
        if (!enabled()) {
            throw new IllegalStateException("fallback disabled: no API key");
        }
        CalendarificResponse response = restClient.get()
                .uri("/api/v2/holidays?api_key={key}&country={country}&year={year}", apiKey, country, year)
                .retrieve()
                .body(CalendarificResponse.class);
        if (response == null || response.response() == null || response.response().holidays() == null) {
            return List.of();
        }
        return response.response().holidays().stream()
                .map(h -> new Holiday(
                        // iso is a plain date or a datetime; the first 10 chars are always the date
                        LocalDate.parse(h.date().iso().substring(0, 10)),
                        h.name(), h.name(),
                        h.type() != null && h.type().contains(NATIONAL_HOLIDAY_TYPE)))
                .toList();
    }

    /** Subset of the Calendarific response shape; unknown fields ignored. */
    record CalendarificResponse(Payload response) {
    }

    record Payload(List<CalendarificHoliday> holidays) {
    }

    record CalendarificHoliday(String name, String description, IsoDate date, List<String> type) {
    }

    record IsoDate(String iso) {
    }
}
