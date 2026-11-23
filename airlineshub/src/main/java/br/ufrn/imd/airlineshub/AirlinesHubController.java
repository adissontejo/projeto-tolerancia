package br.ufrn.imd.airlineshub;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class AirlinesHubController {

    public record SellRequest(String flight, String day) {}
    public record SellResponse(String transactionId, int seatsRemaining) {}

    private final Instant startedAt = Instant.now();
    private final Catalog catalog;

    public AirlinesHubController(Catalog catalog) {
        this.catalog = catalog;
    }

    // Verifica se há voos no catálogo, se existem os 21 pares (3 voos × 7 dias) e se nenhum estoque está negativo.
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();
        Catalog.HealthCheck check = catalog.checkHealth();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", check.healthy() ? "ok" : "down");
        body.put("uptimeS", uptimeS);
        body.put("flights", check.flights());
        body.put("days", check.days());
        body.put("pairs", check.pairs());
        body.put("expectedPairs", check.expectedPairs());
        body.put("negativePairs", check.negativePairs());
        body.put("seatsAvailable", check.seatsAvailable());

        HttpStatus status = check.healthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/flight")
    public ResponseEntity<?> getFlight(@RequestParam String flight, @RequestParam String day) {
        LocalDate parsedDay;

        try {
            parsedDay = LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-day"));
        }

        Optional<Catalog.FlightAvailability> availability = catalog.findFlight(flight, parsedDay);

        if (availability.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "flight-not-found"));
        }

        return ResponseEntity.ok(availability.get());
    }

    @PostMapping("/sell")
    public ResponseEntity<?> sellFlight(
        @RequestBody SellRequest request,
        @RequestHeader(value = RequestLoggingFilter.CORRELATION_HEADER, required = false) String correlationId
    ) throws InterruptedException {
        long start = System.nanoTime();
        int delayMs = ThreadLocalRandom.current().nextInt(300, 901);

        Thread.sleep(delayMs);

        if (request.flight() == null || request.day() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-request"));
        }

        LocalDate parsedDay;

        try {
            parsedDay = LocalDate.parse(request.day());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-day"));
        }

        Catalog.SaleResult result = catalog.sell(request.flight(), parsedDay);

        Map<String, Object> saleInfo = new LinkedHashMap<>();
        saleInfo.put("flight", request.flight());
        saleInfo.put("day", request.day());
        saleInfo.put("delayMs", delayMs);
        saleInfo.put("result", result.status());

        if (result.status() == Catalog.SaleStatus.SOLD) {
            saleInfo.put("transactionId", result.transactionId());
            saleInfo.put("seatsRemaining", result.seatsRemaining());
        }
        JsonLogger.log(correlationId, "venda.processada", (System.nanoTime() - start) / 1_000_000, saleInfo);

        return switch (result.status()) {
            case SOLD -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new SellResponse(result.transactionId(), result.seatsRemaining()));
            case SOLD_OUT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "sold-out"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "flight-not-found"));
        };
    }
}
