package br.ufrn.imd.fidelity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonLogger {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String service;

    public JsonLogger(String service) {
        this.service = service;
    }

    public void logRequest(String correlationId, String path) {
        log(correlationId, "requisicao.recebida", 0, path, null);
    }

    public void logResponse(String correlationId, long elapsedMs, String path, int status) {
        log(correlationId, "resposta.enviada", elapsedMs, path, status);
    }

    private void log(String correlationId, String event, long elapsedMs, String path, Integer status) {
        Map<String, Object> logEntry = new LinkedHashMap<>();

        logEntry.put("ts", Instant.now().toString());
        logEntry.put("svc", service);
        logEntry.put("cid", correlationId);
        logEntry.put("event", event);
        logEntry.put("ms", elapsedMs);
        logEntry.put("path", path);

        if (status != null) {
            logEntry.put("status", status);
        }

        try {
            System.out.println(
                OBJECT_MAPPER.writeValueAsString(logEntry)
            );
        } catch (JsonProcessingException exception) {
            System.err.println(
                "Failed to serialize log: " + exception.getMessage()
            );
        }
    }
}
