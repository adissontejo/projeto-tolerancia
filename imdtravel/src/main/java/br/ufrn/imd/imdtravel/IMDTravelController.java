package br.ufrn.imd.imdtravel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class IMDTravelController {

    public record BuyTicketRequest(String flight, String day, String user) {}
    public record BuyTicketResponse(String transactionId, String flight, String day, String user, BigDecimal valueUSD, BigDecimal valueBRL, BigDecimal rate, String rateAsOf, long bonus) {}
    private record FlightResponse(String flight, String day, BigDecimal priceUSD, int seatsAvailable) {}
    private record ConvertResponse(BigDecimal rate, String asOf) {}
    private record SellRequest(String flight, String day) {}
    private record SellResponse(String transactionId, int seatsRemaining) {}
    private record BonusRequest(String user, long bonus) {}

    private final Instant startedAt = Instant.now();
    private final RestClient airlinesHub;
    private final RestClient exchange;
    private final RestClient fidelity;
    private final SalesLog salesLog;

    public IMDTravelController(
        @Value("${AIRLINESHUB_URL:http://localhost:8080}") String airlinesHubUrl,
        @Value("${EXCHANGE_URL:http://localhost:8080}") String exchangeUrl,
        @Value("${FIDELITY_URL:http://localhost:8080}") String fidelityUrl,
        SalesLog salesLog
    ) {
        this.airlinesHub = RestClient.create(airlinesHubUrl);
        this.exchange = RestClient.create(exchangeUrl);
        this.fidelity = RestClient.create(fidelityUrl);
        this.salesLog = salesLog;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        long uptimeS = Duration.between(startedAt, Instant.now()).getSeconds();
        boolean salesWritable = salesLog.isWritable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", salesWritable ? "ok" : "down");
        body.put("uptimeS", uptimeS);
        body.put("salesWritable", salesWritable);

        HttpStatus status = salesWritable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    @PostMapping("/buyTicket")
    public ResponseEntity<?> buyTicket(
        @RequestBody BuyTicketRequest request,
        @RequestAttribute(RequestLoggingFilter.CORRELATION_HEADER) String correlationId
    ) throws IOException {
        long start = System.nanoTime();

        if (request.flight() == null || request.day() == null || request.day().isBlank()
            || request.user() == null || request.user().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-request"));
        }

        FlightResponse flight = airlinesHub.get()
            .uri("/flight?flight={flight}&day={day}", request.flight(), request.day())
            .header(RequestLoggingFilter.CORRELATION_HEADER, correlationId)
            .retrieve()
            .body(FlightResponse.class);

        ConvertResponse conversion = exchange.get()
            .uri("/convert")
            .header(RequestLoggingFilter.CORRELATION_HEADER, correlationId)
            .retrieve()
            .body(ConvertResponse.class);

        SellResponse sale = airlinesHub.post()
            .uri("/sell")
            .header(RequestLoggingFilter.CORRELATION_HEADER, correlationId)
            .body(new SellRequest(request.flight(), request.day()))
            .retrieve()
            .body(SellResponse.class);

        long bonus = flight.priceUSD().longValue();

        fidelity.post()
            .uri("/bonus")
            .header(RequestLoggingFilter.CORRELATION_HEADER, correlationId)
            .body(new BonusRequest(request.user(), bonus))
            .retrieve()
            .toBodilessEntity();

        BigDecimal valueBRL = flight.priceUSD().multiply(conversion.rate()).setScale(2, RoundingMode.HALF_UP);

        salesLog.append(new SalesLog.Sale(correlationId, sale.transactionId(), request.flight(), request.day(),
            request.user(), flight.priceUSD(), valueBRL, Instant.now().toString()));

        Map<String, Object> saleInfo = new LinkedHashMap<>();
        saleInfo.put("transactionId", sale.transactionId());
        saleInfo.put("flight", request.flight());
        saleInfo.put("day", request.day());
        saleInfo.put("user", request.user());
        saleInfo.put("valueBRL", valueBRL);
        JsonLogger.log(correlationId, "venda.registrada", (System.nanoTime() - start) / 1_000_000, saleInfo);

        return ResponseEntity.status(HttpStatus.CREATED).body(new BuyTicketResponse(sale.transactionId(),
            request.flight(), request.day(), request.user(), flight.priceUSD(), valueBRL, conversion.rate(),
            conversion.asOf(), bonus));
    }

    @GetMapping("/sales")
    public List<SalesLog.Sale> sales() throws IOException {
        return salesLog.findAll();
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not-found"));
    }

    @ExceptionHandler(HttpClientErrorException.Conflict.class)
    public ResponseEntity<Map<String, String>> soldOut() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "sold-out"));
    }

    @ExceptionHandler(HttpClientErrorException.BadRequest.class)
    public ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid-request"));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> dependencyFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "dependency-failure"));
    }
}
