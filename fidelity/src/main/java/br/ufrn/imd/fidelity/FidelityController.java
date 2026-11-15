package br.ufrn.imd.fidelity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FidelityController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "application", "fidelity",
                "status", "UP"
        );
    }
}
