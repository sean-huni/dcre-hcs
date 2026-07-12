package za.co.fnb.dcre.hcs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Nager.Date API adapter (https://date.nager.at). Non-2xx responses throw,
 * failing the job: level-triggered, the next 6h clock window retries (R-38).
 */
@Component
public class NagerClient {

    private final RestClient restClient;

    public NagerClient(@Value("${dcre.hcs.base-url:https://date.nager.at}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    public List<NagerHoliday> fetch(int year, String country) {
        NagerHoliday[] holidays = restClient.get()
                .uri("/api/v3/PublicHolidays/{y}/{c}", year, country)
                .retrieve()
                .body(NagerHoliday[].class);
        return holidays == null ? List.of() : List.of(holidays);
    }

    /** Subset of the Nager PublicHoliday shape; unknown fields ignored. */
    public record NagerHoliday(String date, String localName, String name, boolean global) {
    }
}
