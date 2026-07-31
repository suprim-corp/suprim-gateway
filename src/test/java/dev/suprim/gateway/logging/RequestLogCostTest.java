package dev.suprim.gateway.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Failed requests must not be billed in any cost aggregate. */
class RequestLogCostTest {

	private JdbcTemplate jdbc;
	private RequestLogRepository repository;

	@BeforeEach
	void setUp() {
		SingleConnectionDataSource ds = new SingleConnectionDataSource(
				"jdbc:sqlite::memory:", true);
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("""
				CREATE TABLE request_logs (
				    id TEXT PRIMARY KEY, virtual_key_id TEXT, account_id TEXT,
				    model TEXT, requested_model TEXT, status INTEGER NOT NULL,
				    prompt_tokens INTEGER, completion_tokens INTEGER, total_tokens INTEGER,
				    latency_ms INTEGER, first_token_ms INTEGER, streaming INTEGER,
				    client_ip TEXT, error_message TEXT, credits REAL,
				    created_at INTEGER NOT NULL)
				""");
		repository = new RequestLogRepository(jdbc);
	}

	private void insert(String id, int status) {
		repository.insert(RequestLog.builder()
		                            .id(id)
		                            .model("claude-sonnet-4-5")
		                            .status(status)
		                            .promptTokens(1_000_000)
		                            .completionTokens(1_000_000)
		                            .totalTokens(2_000_000)
		                            .createdAt(System.currentTimeMillis())
		                            .build());
	}

	@Test
	void failedRequestsAreExcludedFromCostAggregates() {
		insert("ok", 200);
		insert("rate-limited", 429);
		insert("upstream-error", 500);

		double onlySuccessCost = 0.000003 * 1_000_000 + 0.000015 * 1_000_000;

		assertThat(repository.sumCostSince(0)).isEqualTo(onlySuccessCost);
		assertThat(cost(repository.timeSeriesHourly(24))).isEqualTo(onlySuccessCost);
		assertThat(cost(repository.modelUsage())).isEqualTo(onlySuccessCost);
	}

	private double cost(List<Map<String, Object>> rows) {
		return rows.stream()
		           .mapToDouble(r -> ((Number) r.get("cost")).doubleValue())
		           .sum();
	}
}
