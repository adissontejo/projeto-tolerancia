package br.ufrn.imd.imdtravel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonLogger {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SERVICE = "imdtravel";

    private JsonLogger() {}

    public static void log(String correlationId, String event, long elapsedMs, Map<String, Object> extra) {
        Map<String, Object> logEntry = new LinkedHashMap<>();

        logEntry.put("ts", Instant.now().toString());
        logEntry.put("svc", SERVICE);
        logEntry.put("cid", correlationId);
        logEntry.put("event", event);
        logEntry.put("ms", elapsedMs);

        if (extra != null) {
            logEntry.putAll(extra);
        }

        try {
            System.out.println(OBJECT_MAPPER.writeValueAsString(logEntry));
        } catch (JsonProcessingException exception) {
            System.err.println("Failed to serialize log: " + exception.getMessage());
        }
    }
}
