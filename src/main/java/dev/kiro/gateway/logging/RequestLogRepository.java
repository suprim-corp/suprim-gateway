package dev.kiro.gateway.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
class RequestLogRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<RequestLog> mapper = (rs, rowNum) -> new RequestLog(
            rs.getString("id"),
            rs.getString("virtual_key_id"),
            rs.getString("account_id"),
            rs.getString("model"),
            rs.getString("requested_model"),
            rs.getInt("status"),
            rs.getObject("prompt_tokens") != null ? rs.getInt("prompt_tokens") : null,
            rs.getObject("completion_tokens") != null ? rs.getInt("completion_tokens") : null,
            rs.getObject("total_tokens") != null ? rs.getInt("total_tokens") : null,
            rs.getObject("latency_ms") != null ? rs.getInt("latency_ms") : null,
            rs.getObject("first_token_ms") != null ? rs.getInt("first_token_ms") : null,
            rs.getObject("streaming") != null ? rs.getInt("streaming") == 1 : null,
            rs.getString("client_ip"),
            rs.getString("error_message"),
            rs.getLong("created_at")
    );

    RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(RequestLog log) {
        jdbc.update("""
                INSERT INTO request_logs (id, virtual_key_id, account_id, model, requested_model, status,
                    prompt_tokens, completion_tokens, total_tokens, latency_ms, first_token_ms,
                    streaming, client_ip, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                log.id(), log.virtualKeyId(), log.accountId(), log.model(), log.requestedModel(),
                log.status(), log.promptTokens(), log.completionTokens(), log.totalTokens(),
                log.latencyMs(), log.firstTokenMs(),
                log.streaming() != null ? (log.streaming() ? 1 : 0) : null,
                log.clientIp(), log.errorMessage(), log.createdAt());
    }

    List<RequestLog> findAll(int limit, int offset) {
        return jdbc.query("SELECT * FROM request_logs ORDER BY created_at DESC LIMIT ? OFFSET ?", mapper, limit, offset);
    }

    int count() {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs", Integer.class);
        return result != null ? result : 0;
    }

    int countSince(long since) {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs WHERE created_at >= ?", Integer.class, since);
        return result != null ? result : 0;
    }

    int countErrorsSince(long since) {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs WHERE created_at >= ? AND status >= 400", Integer.class, since);
        return result != null ? result : 0;
    }

    long sumTokensSince(long since) {
        Long result = jdbc.queryForObject("SELECT COALESCE(SUM(total_tokens), 0) FROM request_logs WHERE created_at >= ?", Long.class, since);
        return result != null ? result : 0;
    }

    double sumCostSince(long since) {
        Double result = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    COALESCE(prompt_tokens, 0) * 0.000003 + COALESCE(completion_tokens, 0) * 0.000015
                ), 0) FROM request_logs WHERE created_at >= ?
                """, Double.class, since);
        return result != null ? result : 0;
    }

    Double avgLatencySince(long since) {
        return jdbc.queryForObject(
                "SELECT AVG(latency_ms) FROM request_logs WHERE created_at >= ? AND latency_ms IS NOT NULL",
                Double.class, since);
    }

    List<Map<String, Object>> timeSeriesHourly(int hours) {
        long since = System.currentTimeMillis() - (long) hours * 3600_000;
        return jdbc.queryForList("""
                SELECT (created_at / 3600000) * 3600000 AS bucket,
                    COUNT(*) AS requests,
                    SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errors,
                    COALESCE(SUM(total_tokens), 0) AS tokens,
                    COALESCE(SUM(COALESCE(prompt_tokens, 0) * 0.000003 + COALESCE(completion_tokens, 0) * 0.000015), 0) AS cost
                FROM request_logs WHERE created_at >= ?
                GROUP BY bucket ORDER BY bucket
                """, since);
    }

    List<Map<String, Object>> modelUsage() {
        return jdbc.queryForList("""
                SELECT model, COUNT(*) AS requests, COALESCE(SUM(total_tokens), 0) AS tokens,
                    COALESCE(SUM(COALESCE(prompt_tokens, 0) * 0.000003 + COALESCE(completion_tokens, 0) * 0.000015), 0) AS cost
                FROM request_logs GROUP BY model ORDER BY requests DESC LIMIT 10
                """);
    }

    List<Map<String, Object>> topKeys() {
        return jdbc.queryForList("""
                SELECT vk.name, COUNT(*) AS requests, COALESCE(SUM(rl.total_tokens), 0) AS tokens
                FROM request_logs rl JOIN virtual_keys vk ON rl.virtual_key_id = vk.id
                GROUP BY rl.virtual_key_id ORDER BY requests DESC LIMIT 10
                """);
    }
}
