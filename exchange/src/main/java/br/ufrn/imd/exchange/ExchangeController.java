package br.ufrn.imd.exchange;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ExchangeController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "application", "exchange",
                "status", "UP"
        );
    }
}
