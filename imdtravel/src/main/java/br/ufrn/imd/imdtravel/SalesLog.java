package br.ufrn.imd.imdtravel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class SalesLog {

    public record Sale(String correlationId, String transactionId, String flight, String day, String user, BigDecimal valueUSD, BigDecimal valueBRL, String ts) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;
    private final Path file;

    public SalesLog(@Value("${SALES_DATA_DIR:data/sales}") String salesDataDir) throws IOException {
        this.dir = Path.of(salesDataDir);
        this.file = dir.resolve("sales.jsonl");
        Files.createDirectories(dir);
    }

    public synchronized void append(Sale sale) throws IOException {
        Files.writeString(file, mapper.writeValueAsString(sale) + System.lineSeparator(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<Sale> findAll() throws IOException {
        List<Sale> sales = new ArrayList<>();

        if (Files.notExists(file)) {
            return sales;
        }

        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                sales.add(mapper.readValue(line, Sale.class));
            }
        }

        return sales;
    }

    public boolean isWritable() {
        return Files.isWritable(dir);
    }
}
