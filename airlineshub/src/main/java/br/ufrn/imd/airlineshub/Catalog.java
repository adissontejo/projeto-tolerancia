package br.ufrn.imd.airlineshub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    public record Flight(Integer id, String origin, String destination, BigDecimal priceUSD) {}
    public record FlightDay(Integer flightId, LocalDate day) {}

    public record FlightAvailability(Integer flight, LocalDate day, BigDecimal priceUSD, int seatsAvailable) {}

    public enum SaleStatus { SOLD, SOLD_OUT, NOT_FOUND }
    public record SaleResult(SaleStatus status, String transactionId, int seatsRemaining) {}

    public record HealthCheck(boolean healthy, int flights, int days, int pairs, int expectedPairs,
                              int negativePairs, int seatsAvailable) {}

    private final List<Flight> flights;
    private final List<LocalDate> days;
    private final Map<FlightDay, AtomicInteger> seats;

    public Catalog(
        @Value("${FLIGHT_CATALOG}") String flightCatalog,
        @Value("${CATALOG_DAYS}") int catalogDays,
        @Value("${SEATS_PER_FLIGHT_DAY}") int seatsPerFlightDay
    ) throws Exception {
        this.flights = parseFlights(flightCatalog);
        this.days = buildDays(catalogDays);
        this.seats = buildSeats(this.flights, this.days, seatsPerFlightDay);

        Map<String, Object> catalogInfo = new LinkedHashMap<>();
        catalogInfo.put("flights", flights.size());
        catalogInfo.put("firstDay", days.get(0).toString());
        catalogInfo.put("lastDay", days.get(days.size() - 1).toString());
        catalogInfo.put("pairs", seats.size());
        catalogInfo.put("seatsPerFlightDay", seatsPerFlightDay);
        JsonLogger.log(null, "catalogo.carregado", 0, catalogInfo);
    }

    private List<Flight> parseFlights(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, new TypeReference<List<Flight>>() {});
    }

    private List<LocalDate> buildDays(int count) {
        LocalDate today = LocalDate.now();
        List<LocalDate> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(today.plusDays(i));
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

    public Optional<FlightAvailability> findFlight(int flightId, LocalDate day) {
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

    public SaleResult sell(int flightId, LocalDate day) {
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
