package dev.suprim.gateway.provider;

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
import java.util.Optional;

@Component
public class CredentialStore {

	private static final Logger log = LoggerFactory.getLogger(
			CredentialStore.class);
	private static final Path DEFAULT_STORE_PATH = Path.of(
			System.getenv("DATABASE_PATH") != null
					? Path.of(System.getenv("DATABASE_PATH"))
					      .getParent()
					      .resolve("credentials.json")
					      .toString()
					: "./data/credentials.json"
	);
	private final Path storePath;
	private final ObjectMapper mapper = new ObjectMapper();

	public CredentialStore() {
		this(DEFAULT_STORE_PATH);
	}

	public CredentialStore(Path storePath) {
		this.storePath = storePath;
	}

	public List<StoredAccount> load() {
		List<StoredAccount> accounts = new ArrayList<>();
		if (!Files.exists(storePath)) return accounts;
		try {
			JsonNode root = mapper.readTree(Files.readString(storePath));
			JsonNode arr = root.get("accounts");
			if (arr == null || !arr.isArray()) return accounts;

			for (JsonNode node : arr) {
				if (node.has("profileArn") || node.has("clientId") || node.has(
						"authType")) {
					log.warn(
							"[CredStore] Legacy camelCase format detected, deleting file");
					Files.delete(storePath);
					return List.of();
				}

				accounts.add(
						StoredAccount.builder()
						             .name(textOrNull(node, "name"))
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
						             .provider(textOrNull(node, "provider"))
						             .projectId(textOrNull(node, "project_id"))
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
			Files.createDirectories(storePath.getParent());
			ObjectNode root = mapper.createObjectNode();
			ArrayNode arr = root.putArray("accounts");
			for (StoredAccount acc : accounts) {
				ObjectNode node = arr.addObject();
				if (acc.name() != null) node.put("name", acc.name());
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
				if (acc.provider() != null) node.put(
						"provider",
						acc.provider()
				);
				if (acc.projectId() != null) node.put(
						"project_id",
						acc.projectId()
				);
			}
			Files.writeString(
					storePath,
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
				updated.add(merge(existing, account));
				replaced = true;
			} else {
				updated.add(existing);
			}
		}
		if (!replaced) updated.add(account);
		save(updated);
	}

	private static StoredAccount merge(
			StoredAccount existing,
			StoredAccount incoming
	) {
		return StoredAccount.builder()
		                    .name(
				                    Optional.ofNullable(incoming.name())
				                            .orElse(existing.name())
		                    )
		                    .profileArn(
				                    Optional.ofNullable(incoming.profileArn())
				                            .orElse(existing.profileArn())
		                    )
		                    .authType(
				                    Optional.ofNullable(incoming.authType())
				                            .orElse(existing.authType())
		                    )
		                    .clientId(
				                    Optional.ofNullable(incoming.clientId())
				                            .orElse(existing.clientId())
		                    )
		                    .clientSecret(
				                    Optional.ofNullable(incoming.clientSecret())
				                            .orElse(existing.clientSecret())
		                    )
		                    .accessToken(
				                    Optional.ofNullable(incoming.accessToken())
				                            .orElse(existing.accessToken())
		                    )
		                    .refreshToken(
				                    Optional.ofNullable(incoming.refreshToken())
				                            .orElse(existing.refreshToken())
		                    )
		                    .expiresAt(
				                    Optional.ofNullable(incoming.expiresAt())
				                            .orElse(existing.expiresAt())
		                    )
		                    .scopes(
				                    Optional.ofNullable(incoming.scopes())
				                            .orElse(existing.scopes())
		                    )
		                    .region(
				                    Optional.ofNullable(incoming.region())
				                            .orElse(existing.region())
		                    )
		                    .apiRegion(
				                    Optional.ofNullable(incoming.apiRegion())
				                            .orElse(existing.apiRegion())
		                    )
		                    .provider(
				                    Optional.ofNullable(incoming.provider())
				                            .orElse(existing.provider())
		                    )
		                    .projectId(
				                    Optional.ofNullable(incoming.projectId())
				                            .orElse(existing.projectId())
		                    )
		                    .build();
	}

	private static boolean matches(
			StoredAccount existing,
			StoredAccount incoming
	) {
		if (incoming.provider() != null && existing.provider() != null) {
			if (!existing.provider().equals(incoming.provider())) return false;
			if (incoming.name() != null && existing.name() != null) {
				return existing.name().equals(incoming.name());
			}
			return existing.clientId() != null
			       && existing.clientId().equals(incoming.clientId());
		}
		if (incoming.profileArn() != null && existing.profileArn() != null) {
			return existing.profileArn().equals(incoming.profileArn());
		}
		if (incoming.clientId() != null && existing.clientId() != null) {
			return existing.clientId().equals(incoming.clientId());
		}
		return false;
	}
	public boolean exists() {
		return Files.exists(storePath);
	}

	public Optional<StoredAccount> findByProvider(String provider) {
		return load().stream()
		             .filter(acc -> provider.equals(acc.provider()))
		             .findFirst();
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
