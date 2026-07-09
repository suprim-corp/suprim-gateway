package dev.suprim.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class KiroCredentialStore {

	private static final Logger log = LoggerFactory.getLogger(
			KiroCredentialStore.class);
	private static final Path STORE_PATH = Path.of("./data/credentials.json");
	private final ObjectMapper mapper = new ObjectMapper();

	public List<StoredAccount> load() {
		List<StoredAccount> accounts = new ArrayList<>();
		if (!Files.exists(STORE_PATH)) return accounts;
		try {
			JsonNode root = mapper.readTree(Files.readString(STORE_PATH));
			JsonNode arr = root.get("accounts");
			if (arr == null || !arr.isArray()) return accounts;

			for (JsonNode node : arr) {
				if (node.has("profileArn") || node.has("clientId") || node.has(
						"authType")) {
					log.warn(
							"[CredStore] Legacy camelCase format detected, deleting file");
					Files.delete(STORE_PATH);
					return List.of();
				}

				accounts.add(
						StoredAccount.builder()
						             .profileArn(
								             textOrNull(
										             node,
										             "profile_arn"
								             )
						             )
						             .authType(textOrNull(node, "auth_type"))
						             .clientId(textOrNull(node, "client_id"))
						             .clientSecret(
								             textOrNull(
										             node,
										             "client_secret"
								             )
						             )
						             .accessToken(
								             textOrNull(
										             node,
										             "access_token"
								             )
						             )
						             .refreshToken(
								             textOrNull(
										             node,
										             "refresh_token"
								             )
						             )
						             .expiresAt(parseExpires(node))
						             .scopes(parseScopes(node.get("scopes")))
						             .region(textOrNull(node, "region"))
						             .apiRegion(textOrNull(node, "api_region"))
						             .build()
				);
			}
		} catch (Exception e) {
			log.error("[CredStore] Failed to load: {}", e.getMessage());
		}
		return accounts;
	}

	public void save(List<StoredAccount> accounts) {
		try {
			Files.createDirectories(STORE_PATH.getParent());
			ObjectNode root = mapper.createObjectNode();
			ArrayNode arr = root.putArray("accounts");
			for (StoredAccount acc : accounts) {
				ObjectNode node = arr.addObject();
				node.put("profile_arn", acc.profileArn());
				node.put("auth_type", acc.authType());
				node.put("client_id", acc.clientId());
				node.put("client_secret", acc.clientSecret());
				node.put("access_token", acc.accessToken());
				node.put("refresh_token", acc.refreshToken());
				if (acc.expiresAt() != null) node.put(
						"expires_at",
						acc.expiresAt().toString()
				);
				if (acc.scopes() != null) {
					ArrayNode scopesArr = node.putArray("scopes");
					for (String s : acc.scopes()) scopesArr.add(s);
				}
				node.put("region", acc.region());
				node.put("api_region", acc.apiRegion());
			}
			Files.writeString(
					STORE_PATH,
					mapper.writerWithDefaultPrettyPrinter()
					      .writeValueAsString(root)
			);
		} catch (Exception e) {
			log.error("[CredStore] Failed to save: {}", e.getMessage());
		}
	}

	public void upsert(StoredAccount account) {
		List<StoredAccount> accounts = load();
		List<StoredAccount> updated = new ArrayList<>(accounts.size() + 1);
		boolean replaced = false;
		for (StoredAccount existing : accounts) {
			if (matches(existing, account)) {
				updated.add(account);
				replaced = true;
			} else {
				updated.add(existing);
			}
		}
		if (!replaced) updated.add(account);
		save(updated);
	}

	private static boolean matches(
			StoredAccount existing,
			StoredAccount incoming
	) {
		if (incoming.profileArn() != null && existing.profileArn() != null) {
			return existing.profileArn().equals(incoming.profileArn());
		}
		if (incoming.clientId() != null && existing.clientId() != null) {
			return existing.clientId().equals(incoming.clientId());
		}
		return false;
	}

	public boolean exists() {
		return Files.exists(STORE_PATH);
	}

	private static Instant parseExpires(JsonNode node) {
		String val = textOrNull(node, "expires_at");
		return val != null ? Instant.parse(val) : null;
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull() ? node.get(field)
		                                                          .asString() : null;
	}

	private static String[] parseScopes(JsonNode node) {
		if (node == null || !node.isArray()) {
			return null;
		}
		String[] scopes = new String[node.size()];
		for (int i = 0; i < node.size(); i++) {
			scopes[i] = node.get(i).asString();
		}

		return scopes;
	}
}
