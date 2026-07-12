package za.co.fnb.dcre.hcs.bdd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Mutable Nager.Date stub for the BDD suite (same JDK httpserver + fixture
 * JSON approach as HcsJobTest): standard ZA fixtures by default, plus
 * per-scenario local-name overrides and an unconditional-500 failure mode.
 */
final class NagerStub {

    record Fixture(String date, String localName, String name) {
    }

    static final List<Fixture> ZA_2026 = List.of(
            new Fixture("2026-01-01", "Nuwejaarsdag", "New Year's Day"),
            new Fixture("2026-12-25", "Kersfees", "Christmas Day"));

    static final List<Fixture> ZA_2027 = List.of(
            new Fixture("2027-01-01", "Nuwejaarsdag", "New Year's Day"));

    private static final AtomicBoolean FAIL = new AtomicBoolean(false);
    private static final Map<String, String> LOCAL_NAME_OVERRIDES = new ConcurrentHashMap<>();
    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.createContext("/api/v3/PublicHolidays/2026/ZA", exchange -> respond(exchange, ZA_2026));
        SERVER.createContext("/api/v3/PublicHolidays/2027/ZA", exchange -> respond(exchange, ZA_2027));
        SERVER.start();
    }

    private NagerStub() {
    }

    static String url() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    static void reset() {
        FAIL.set(false);
        LOCAL_NAME_OVERRIDES.clear();
    }

    static void failUpstream() {
        FAIL.set(true);
    }

    static void overrideLocalName(String date, String localName) {
        LOCAL_NAME_OVERRIDES.put(date, localName);
    }

    private static void respond(HttpExchange exchange, List<Fixture> fixtures) throws IOException {
        boolean fail = FAIL.get();
        byte[] bytes = (fail ? "boom" : json(fixtures)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String json(List<Fixture> fixtures) {
        return fixtures.stream()
                .map(f -> "{\"date\":\"" + f.date() + "\",\"localName\":\""
                        + LOCAL_NAME_OVERRIDES.getOrDefault(f.date(), f.localName())
                        + "\",\"name\":\"" + f.name() + "\",\"countryCode\":\"ZA\",\"global\":true}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
