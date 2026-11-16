package br.ufrn.imd.exchange;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class ExchangeController {
    private final Instant startedAt = Instant.now();
    private final JsonLogger logger = new JsonLogger("exchange");

    private static final double MIN_RATE = 5.0;
    private static final double MAX_RATE = 6.0;
    private static final double MAX_VARIATION = 0.003;

    private volatile double currentExchangeRate;
    private volatile Instant lastUpdated;

    public ExchangeController(@Value("${INITIAL_EXCHANGE_RATE:5.50}") double initialExchangeRate) {
        if (initialExchangeRate < MIN_RATE || initialExchangeRate > MAX_RATE) {
            throw new IllegalArgumentException(
                "Initial exchange rate must be between " + MIN_RATE + " and " + MAX_RATE
            );
        }

        this.currentExchangeRate = initialExchangeRate;
        this.lastUpdated = Instant.now();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();

        return Map.of(
            "status", "ok",
            "uptimeS", uptimeS
        );
    }

    @GetMapping("/convert")
    public synchronized ConversionResponse convert() {
        long start = System.nanoTime();

        logger.logRequest(null, "/convert");

        ConversionResponse response = new ConversionResponse(currentExchangeRate, lastUpdated);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        logger.logResponse(null, elapsedMs, "/convert", 200);

        return response;
    }

    @Scheduled(fixedRate = 10_000)
    public synchronized void updateRate() {
        double minVariation = Math.max(-MAX_VARIATION, MIN_RATE / currentExchangeRate - 1);
        double maxVariation = Math.min(MAX_VARIATION, MAX_RATE / currentExchangeRate - 1);
        double variation = ThreadLocalRandom.current().nextDouble(minVariation, maxVariation);

        currentExchangeRate = currentExchangeRate * (1 + variation);
        lastUpdated = Instant.now();
    }

    private record ConversionResponse(double rate, Instant asOf) {}
}
