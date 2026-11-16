package br.ufrn.imd.imdtravel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
public class IMDTravelController {
    private final Instant startedAt = Instant.now();

    @GetMapping("/health")
    public Map<String, Object> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();

        return Map.of(
            "status", "ok",
            "uptimeS", uptimeS
        );
    }
}
