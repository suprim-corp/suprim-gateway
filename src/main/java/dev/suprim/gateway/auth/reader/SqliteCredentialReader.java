package dev.suprim.gateway.auth.reader;

import dev.suprim.gateway.auth.KiroCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SqliteCredentialReader {

	private static final Logger log = LoggerFactory.getLogger(
			SqliteCredentialReader.class
	);
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String[] TOKEN_KEYS = {
			"kirocli:social:token",
			"kirocli:odic:token",
			"codewhisperer:odic:token"
	};

	private static final String TOKEN_QUERY =
			"SELECT key, value FROM auth_kv WHERE key IN (?, ?, ?)";

	private static final String DEVICE_REG_QUERY =
			"SELECT value FROM auth_kv WHERE key = 'kirocli:odic:device-registration'";

	private SqliteCredentialReader() {}

	public static Optional<KiroCredentials> read(Path path) {
		if (!Files.exists(path)) {
			log.warn("[Auth] SQLite DB not found: {}", path);
			return Optional.empty();
		}

		String jdbcUrl = "jdbc:sqlite:" + path;
		String clientId = null, clientSecret = null;
		String[] scopes = null;
		String accessToken = null, refreshToken = null;
		Instant expiresAt = null;

		Connection conn;
		Statement statement;

		try {
			conn = DriverManager.getConnection(jdbcUrl);
			statement = conn.createStatement();
			statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
		} catch (SQLException e) {
			log.error(
					"[Auth] Failed to connect to SQLite DB: {}",
					e.getMessage()
			);

			return Optional.empty();
		}

		try {
			ResultSet rs = statement.executeQuery(DEVICE_REG_QUERY);
			if (rs.next()) {
				JsonNode registration = mapper.readTree(rs.getString("value"));

				clientId = textOrNull(registration, "client_id");
				clientSecret = textOrNull(registration, "client_secret");

				if (registration.has("scopes") &&
				    registration.get("scopes").isArray()) {
					scopes = new String[registration.get("scopes").size()];
					for (int i = 0;
					     i < registration.get("scopes").size();
					     i++) {
						scopes[i] = registration.get("scopes")
						                        .get(i)
						                        .asString();
					}
				}
			}
		} catch (Exception e) {
			log.error(
					"[Auth] Failed to read device registration: {}",
					e.getMessage()
			);
		}

		try {
			// single query, pick first match by priority order
			try (PreparedStatement ps = conn.prepareStatement(TOKEN_QUERY)) {
				for (int i = 0; i < TOKEN_KEYS.length; i++) {
					ps.setString(i + 1, TOKEN_KEYS[i]);
				}
				Map<String, JsonNode> rows = new LinkedHashMap<>();
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						rows.put(
								rs.getString("key"),
								mapper.readTree(rs.getString("value"))
						);
					}
				}

				for (String key : TOKEN_KEYS) {
					JsonNode json = rows.get(key);
					if (json == null) continue;

					accessToken = textOrNull(json, "access_token");
					if (accessToken == null) accessToken = textOrNull(
							json,
							"accessToken"
					);

					refreshToken = textOrNull(json, "refresh_token");
					if (refreshToken == null)
						refreshToken = textOrNull(json, "refreshToken");

					String exp = textOrNull(json, "expires_at");
					if (exp == null) exp = textOrNull(json, "expiresAt");
					if (exp != null) expiresAt = Instant.parse(exp);

					if (clientId == null) clientId = textOrNull(
							json,
							"client_id"
					);
					if (clientSecret == null)
						clientSecret = textOrNull(json, "client_secret");

					if (accessToken != null || refreshToken != null) break;
				}
			}
		} catch (Exception e) {
			log.error(
					"[Auth] Failed to read tokens: {}",
					e.getMessage()
			);
		}

		if (accessToken == null && refreshToken == null) {
			return Optional.empty();
		}

		boolean hasClientIdAndSecret = clientId != null && clientSecret != null;

		KiroCredentials.AuthType authType = hasClientIdAndSecret
				? KiroCredentials.AuthType.AWS_SSO_OIDC
				: KiroCredentials.AuthType.KIRO_DESKTOP;

		return Optional.of(
				KiroCredentials.builder()
				               .accessToken(accessToken)
				               .refreshToken(refreshToken)
				               .expiresAt(expiresAt)
				               .clientId(clientId)
				               .clientSecret(clientSecret)
				               .scopes(scopes)
				               .authType(authType)
				               .build()
		);
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull()
				? node.get(field).asString() : null;
	}
}
