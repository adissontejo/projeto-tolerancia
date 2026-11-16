package br.ufrn.imd.fidelity;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class FidelityController {
    private final Instant startedAt = Instant.now();
    private final Map<String, UserPoints> users = new ConcurrentHashMap<>();
    private final JsonLogger logger = new JsonLogger("fidelity");

    @GetMapping("/health")
    public Map<String, Object> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();

        return Map.of(
            "status", "ok",
            "uptimeS", uptimeS
        );
    }

    @PostMapping("/bonus")
    public ResponseEntity<BonusResponse> postMethodName(
        @RequestBody BonusRequest request,
        @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        long start = System.nanoTime();

        logger.logRequest(correlationId, "/bonus");

        if (request.user() == null || request.user().isBlank() || request.bonus() <= 0) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            logger.logResponse(correlationId, elapsedMs, "/bonus", 400);

            return ResponseEntity.badRequest().build();
        }

        BonusEntry entry = new BonusEntry(request.bonus(), correlationId);

        UserPoints userPoints = users.compute(request.user(), (user, current) -> {
            if (current == null) {
                return new UserPoints(request.bonus(), List.of(entry));
            } else {
                long newTotal = current.totalPoints() + request.bonus();

                List<BonusEntry> newEntries = new java.util.ArrayList<>(current.entries());

                newEntries.add(entry);

                return new UserPoints(newTotal, newEntries);
            }
        });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        logger.logResponse(correlationId, elapsedMs, "/bonus", 200);

        return ResponseEntity.ok(new BonusResponse(request.user(), userPoints.totalPoints()));
    }

    @GetMapping("/points")
    public ResponseEntity<PointsResponse> getPoints(@RequestParam String user) {
        long start = System.nanoTime();

        logger.logRequest(null, "/points");

        if (user == null || user.isBlank()) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            logger.logResponse(null, elapsedMs, "/points", 400);

            return ResponseEntity.badRequest().build();
        }

        UserPoints userPoints = users.getOrDefault(user, new UserPoints(0, List.of()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        logger.logResponse(null, elapsedMs, "/points", 200);

        return ResponseEntity.ok(
            new PointsResponse(user, userPoints.totalPoints(), userPoints.entries())
        );
    }

    private record BonusRequest(String user, long bonus) {}

    private record BonusResponse(String user, long totalPoints) {}

    private record PointsResponse(String user, long totalPoints, List<BonusEntry> entries) {}

    private record UserPoints(long totalPoints, List<BonusEntry> entries) {}

    private record BonusEntry(long bonus, String correlationId) {}
}
