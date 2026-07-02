package dev.suprim.gateway.virtualkey;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Repository
class VirtualKeyRepository {

	private final JdbcTemplate jdbc;

	private final RowMapper<VirtualKey> mapper = (rs, rowNum) -> new VirtualKey(
			rs.getString("id"),
			rs.getString("name"),
			rs.getString("key_hash"),
			rs.getString("key_prefix"),
			rs.getString("account_id"),
			rs.getInt("enabled") == 1,
			rs.getObject("revoked_at") !=
			null ? rs.getLong("revoked_at") : null,
			rs.getInt("rate_limit_per_min"),
			rs.getString("allowed_models"),
			rs.getString("budget_period"),
			rs.getObject("budget_tokens") !=
			null ? rs.getInt("budget_tokens") : null,
			rs.getObject("budget_requests") != null ? rs.getInt(
					"budget_requests") : null,
			rs.getObject("budget_cost") !=
			null ? rs.getInt("budget_cost") : null,
			rs.getInt("period_tokens_used"),
			rs.getInt("period_requests_used"),
			rs.getObject("period_reset_at") != null ? rs.getLong(
					"period_reset_at") : null,
			rs.getInt("total_requests"),
			rs.getInt("total_tokens"),
			rs.getObject("last_used_at") !=
			null ? rs.getLong("last_used_at") : null,
			rs.getLong("created_at")
	);

	VirtualKeyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	VirtualKey findByKeyHash(String hash) {
		List<VirtualKey> results = jdbc.query(
				"SELECT * FROM virtual_keys WHERE key_hash = ?",
				mapper,
				hash
		);
		return results.isEmpty() ? null : results.getFirst();
	}

	VirtualKey findById(String id) {
		List<VirtualKey> results = jdbc.query(
				"SELECT * FROM virtual_keys WHERE id = ?",
				mapper,
				id
		);
		return results.isEmpty() ? null : results.getFirst();
	}

	List<VirtualKey> findAll(int limit, int offset) {
		return jdbc.query(
				"SELECT * FROM virtual_keys ORDER BY created_at DESC LIMIT ? OFFSET ?",
				mapper,
				limit,
				offset
		);
	}

	int count() {
		Integer result = jdbc.queryForObject(
				"SELECT COUNT(*) FROM virtual_keys",
				Integer.class
		);
		return result != null ? result : 0;
	}

	void insert(VirtualKey key) {
		jdbc.update(
				"""
						INSERT INTO virtual_keys (id, name, key_hash, key_prefix, account_id, enabled, revoked_at,
						    rate_limit_per_min, allowed_models, budget_period, budget_tokens, budget_requests, budget_cost,
						    period_tokens_used, period_requests_used, period_reset_at, total_requests, total_tokens,
						    last_used_at, created_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""",
				key.id(),
				key.name(),
				key.keyHash(),
				key.keyPrefix(),
				key.accountId(),
				key.enabled() ? 1 : 0,
				key.revokedAt(),
				key.rateLimitPerMin(),
				key.allowedModels(),
				key.budgetPeriod(),
				key.budgetTokens(),
				key.budgetRequests(),
				key.budgetCost(),
				key.periodTokensUsed(),
				key.periodRequestsUsed(),
				key.periodResetAt(),
				key.totalRequests(),
				key.totalTokens(),
				key.lastUsedAt(),
				key.createdAt()
		);
	}

	void updateEnabled(String id, boolean enabled) {
		jdbc.update(
				"UPDATE virtual_keys SET enabled = ? WHERE id = ?",
				enabled ? 1 : 0,
				id
		);
	}

	void revoke(String id) {
		jdbc.update(
				"UPDATE virtual_keys SET revoked_at = ?, enabled = 0 WHERE id = ?",
				System.currentTimeMillis(),
				id
		);
	}

	void updateLimits(
			String id,
			int rateLimitPerMin,
			String budgetPeriod,
			Integer budgetTokens,
			Integer budgetRequests,
			Integer budgetCost
	) {
		jdbc.update(
				"""
						UPDATE virtual_keys SET rate_limit_per_min = ?, budget_period = ?, budget_tokens = ?, budget_requests = ?, budget_cost = ?
						WHERE id = ?
						""",
				rateLimitPerMin,
				budgetPeriod,
				budgetTokens,
				budgetRequests,
				budgetCost,
				id
		);
	}

	void incrementUsage(String id, int tokens) {
		jdbc.update(
				"""
						UPDATE virtual_keys SET total_requests = total_requests + 1, total_tokens = total_tokens + ?,
						    period_requests_used = period_requests_used + 1, period_tokens_used = period_tokens_used + ?,
						    last_used_at = ? WHERE id = ?
						""", tokens, tokens, System.currentTimeMillis(), id
		);
	}

	static String hashKey(String rawKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawKey.getBytes()));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
}
