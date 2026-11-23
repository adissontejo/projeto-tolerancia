package br.ufrn.imd.exchange;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class ExchangeController {
    private final Instant startedAt = Instant.now();

    private static final double MIN_RATE = 5.0;
    private static final double MAX_RATE = 6.0;
    private static final double MAX_VARIATION = 0.003;
    private static final long MAX_UPDATE_AGE_SECONDS = 30;

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
    public ResponseEntity<Map<String, Object>> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();
        Instant now = Instant.now();

        boolean rateValid = Double.isFinite(currentExchangeRate)
            && currentExchangeRate >= MIN_RATE
            && currentExchangeRate <= MAX_RATE;

        boolean lastUpdatedValid = lastUpdated != null
            && !lastUpdated.isAfter(now)
            && Duration.between(lastUpdated, now).getSeconds() <= MAX_UPDATE_AGE_SECONDS;

        boolean healthy = rateValid && lastUpdatedValid;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", healthy ? "ok" : "down");
        body.put("uptimeS", uptimeS);
        body.put("rateValid", rateValid);
        body.put("lastUpdatedValid", lastUpdatedValid);

        HttpStatus status = healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/convert")
    public synchronized ConversionResponse convert() {
        return new ConversionResponse(currentExchangeRate, lastUpdated);
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
