package dev.suprim.gateway.logging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class RequestLogService {

	private final RequestLogRepository repository;
	private final long startTime = System.currentTimeMillis();

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
		double errorRate =
				totalRequests > 0 ? (double) errors / totalRequests : 0;
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
