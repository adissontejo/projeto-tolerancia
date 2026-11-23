package br.ufrn.imd.fidelity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class FidelityController {
    private final Instant startedAt = Instant.now();
    private final Map<String, UserPoints> users = new ConcurrentHashMap<>();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();

        boolean storageHealthy = checkStorage();

        Map<String, Object> body = Map.of(
            "status", storageHealthy ? "ok" : "down",
            "uptimeS", uptimeS,
            "storageHealthy", storageHealthy
        );

        HttpStatus status = storageHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(body);
    }

    private boolean checkStorage() {
        String probeKey = "__health_probe__" + java.util.UUID.randomUUID();
        UserPoints probeValue = new UserPoints(1, List.of());

        try {
            users.put(probeKey, probeValue);

            UserPoints storedValue = users.get(probeKey);
            boolean writeAndReadSucceeded = storedValue == probeValue;

            UserPoints removedValue = users.remove(probeKey);
            boolean removeSucceeded = removedValue == probeValue;

            return writeAndReadSucceeded && removeSucceeded;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            users.remove(probeKey);
        }
    }

    @PostMapping("/bonus")
    public ResponseEntity<BonusResponse> postMethodName(
        @RequestBody BonusRequest request,
        @RequestAttribute(RequestLoggingFilter.CORRELATION_HEADER) String correlationId
    ) {
        if (request.user() == null || request.user().isBlank() || request.bonus() <= 0) {
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

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(new BonusResponse(request.user(), userPoints.totalPoints()));
    }

    @GetMapping("/points")
    public ResponseEntity<PointsResponse> getPoints(@RequestParam String user) {
        if (user == null || user.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        UserPoints userPoints = users.getOrDefault(user, new UserPoints(0, List.of()));

        return ResponseEntity.ok(
            new PointsResponse(user, userPoints.totalPoints(), userPoints.entries())
        );
    }

    private record BonusRequest(String user, long bonus) {}

    private record BonusResponse(String user, long totalPoints) {}

    private record PointsResponse(String user, long totalPoints, List<BonusEntry> entries) {}

    private record UserPoints(long totalPoints, List<BonusEntry> entries) {}

    private record BonusEntry(long bonus, String cid) {}
}
