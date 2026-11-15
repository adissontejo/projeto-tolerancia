package br.ufrn.imd.imdtravel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IMDTravelController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "application", "imdtravel",
                "status", "UP"
        );
    }
}
