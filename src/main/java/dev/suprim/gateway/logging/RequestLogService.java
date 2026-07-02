package dev.suprim.gateway.logging;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RequestLogService {

    private final RequestLogRepository repository;
    private final long startTime = System.currentTimeMillis();

    RequestLogService(RequestLogRepository repository) {
        this.repository = repository;
    }

    public void log(String virtualKeyId, String accountId, String model, String requestedModel,
             int status, Integer promptTokens, Integer completionTokens,
             Integer latencyMs, Integer firstTokenMs, Boolean streaming,
             String clientIp, String errorMessage) {
        int totalTokens = (promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0);
        RequestLog entry = new RequestLog(
                UUID.randomUUID().toString(), virtualKeyId, accountId, model, requestedModel,
                status, promptTokens, completionTokens, totalTokens > 0 ? totalTokens : null,
                latencyMs, firstTokenMs, streaming, clientIp, errorMessage, System.currentTimeMillis()
        );
        repository.insert(entry);
    }

    public List<RequestLog> getLogs(int limit, int offset) {
        return repository.findAll(limit, offset);
    }

    public int getTotal() {
        return repository.count();
    }

    public Map<String, Object> getStats() {
        long now = System.currentTimeMillis();
        long last24h = now - 86_400_000;
        int totalRequests = repository.countSince(last24h);
        int errors = repository.countErrorsSince(last24h);
        long totalTokens = repository.sumTokensSince(last24h);
        double totalCost = repository.sumCostSince(last24h);
        Double avgLatency = repository.avgLatencySince(last24h);
        double errorRate = totalRequests > 0 ? (double) errors / totalRequests : 0;
        long uptimeSeconds = (now - startTime) / 1000;

        return Map.of(
                "totalRequests", totalRequests,
                "totalTokens", totalTokens,
                "totalCost", totalCost,
                "errorRate", errorRate,
                "avgLatencyMs", avgLatency != null ? avgLatency.intValue() : 0,
                "uptimeSeconds", uptimeSeconds
        );
    }

    public List<Map<String, Object>> getTimeSeries(int hours) {
        return repository.timeSeriesHourly(hours);
    }

    public List<Map<String, Object>> getModelUsage() {
        return repository.modelUsage();
    }

    public List<Map<String, Object>> getTopKeys() {
        return repository.topKeys();
    }
}
