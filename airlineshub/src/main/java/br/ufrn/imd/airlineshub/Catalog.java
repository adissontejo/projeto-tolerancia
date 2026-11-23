package br.ufrn.imd.airlineshub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal; // geralmente usado para dinheiro, pois evita problemas de precisão com ponto flutuante
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Catalog {

    public record Flight(String id, BigDecimal priceUSD) {}
    public record FlightDay(String flightId, LocalDate day) {}

    public record FlightAvailability(String flight, LocalDate day, BigDecimal priceUSD, int seatsAvailable) {}

    public enum SaleStatus { SOLD, SOLD_OUT, NOT_FOUND }
    public record SaleResult(SaleStatus status, String transactionId, int seatsRemaining) {}

    public record HealthCheck(boolean healthy, int flights, int days, int pairs, int expectedPairs,
                              int negativePairs, int seatsAvailable) {}

    private final List<Flight> flights;
    private final List<LocalDate> days;
    private final Map<FlightDay, AtomicInteger> seats;

    public Catalog(
        @Value("${CATALOG_FLIGHTS}") String catalogFlights,
        @Value("${CATALOG_BASE_PRICES}") String catalogBasePrices,
        @Value("${CATALOG_START_DATE}") String catalogStartDate,
        @Value("${CATALOG_DAYS}") int catalogDays,
        @Value("${CATALOG_SEATS}") int seatsPerFlightDay
    ) {
        this.flights = parseFlights(catalogFlights, catalogBasePrices);
        this.days = buildDays(LocalDate.parse(catalogStartDate), catalogDays);
        this.seats = buildSeats(this.flights, this.days, seatsPerFlightDay);

        Map<String, Object> catalogInfo = new LinkedHashMap<>();
        catalogInfo.put("flights", flights.size());
        catalogInfo.put("firstDay", days.get(0).toString());
        catalogInfo.put("lastDay", days.get(days.size() - 1).toString());
        catalogInfo.put("pairs", seats.size());
        catalogInfo.put("seatsPerFlightDay", seatsPerFlightDay);
        JsonLogger.log(null, "catalogo.carregado", 0, catalogInfo);
    }

    private List<Flight> parseFlights(String ids, String prices) {
        String[] flightIds = ids.split(",");
        String[] basePrices = prices.split(",");
        List<Flight> result = new ArrayList<>();

        for (int i = 0; i < flightIds.length; i++) {
            result.add(new Flight(flightIds[i].trim(), new BigDecimal(basePrices[i].trim())));
        }

        return result;
    }

    private List<LocalDate> buildDays(LocalDate startDate, int count) {
        List<LocalDate> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(startDate.plusDays(i));
        }

        return result;
    }

    private Map<FlightDay, AtomicInteger> buildSeats(List<Flight> flights, List<LocalDate> days, int seatsPerFlightDay) {
        Map<FlightDay, AtomicInteger> result = new ConcurrentHashMap<>();

        for (Flight flight : flights) {
            for (LocalDate day : days) {
                result.put(new FlightDay(flight.id(), day), new AtomicInteger(seatsPerFlightDay));
            }
        }

        return result;
    }

    public Optional<FlightAvailability> findFlight(String flightId, LocalDate day) {
        AtomicInteger available = seats.get(new FlightDay(flightId, day));

        if (available == null) {
            return Optional.empty();
        }

        Flight flight = flights.stream()
            .filter(f -> f.id().equals(flightId))
            .findFirst()
            .orElseThrow();

        return Optional.of(new FlightAvailability(flightId, day, flight.priceUSD(), available.get()));
    }

    public SaleResult sell(String flightId, LocalDate day) {
        AtomicInteger available = seats.get(new FlightDay(flightId, day));

        if (available == null) {
            return new SaleResult(SaleStatus.NOT_FOUND, null, 0);
        }

        while (true) {
            int current = available.get();

            if (current <= 0) {
                return new SaleResult(SaleStatus.SOLD_OUT, null, 0);
            }

            if (available.compareAndSet(current, current - 1)) {
                return new SaleResult(SaleStatus.SOLD, UUID.randomUUID().toString(), current - 1);
            }
        }
    }

    // usado pelo /health: confere se o estoque em memória está íntegro
    public HealthCheck checkHealth() {
        int expectedPairs = flights.size() * days.size();
        int negativePairs = 0;
        int seatsAvailable = 0;

        for (AtomicInteger available : seats.values()) {
            int value = available.get();
            if (value < 0) {
                negativePairs++;
            }
            seatsAvailable += value;
        }

        boolean healthy = !flights.isEmpty() && seats.size() == expectedPairs && negativePairs == 0;

        return new HealthCheck(healthy, flights.size(), days.size(), seats.size(), expectedPairs, negativePairs, seatsAvailable);
    }
}
