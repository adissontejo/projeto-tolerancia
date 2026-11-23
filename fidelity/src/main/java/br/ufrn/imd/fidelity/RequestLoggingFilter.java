package br.ufrn.imd.fidelity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        String correlationId = request.getHeader(CORRELATION_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(CORRELATION_HEADER, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        Map<String, Object> requestInfo = new LinkedHashMap<>();
        requestInfo.put("method", request.getMethod());
        requestInfo.put("path", request.getRequestURI());
        if (request.getQueryString() != null) {
            requestInfo.put("query", request.getQueryString());
        }

        JsonLogger.log(correlationId, "requisicao.recebida", 0, requestInfo);

        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            Map<String, Object> responseInfo = new LinkedHashMap<>(requestInfo);
            responseInfo.put("status", status);
            JsonLogger.log(correlationId, "resposta.enviada", elapsedMs, responseInfo);
        }
    }
}
