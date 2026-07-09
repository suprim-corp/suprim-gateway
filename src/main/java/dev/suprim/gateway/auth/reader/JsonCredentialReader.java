package dev.suprim.gateway.auth.reader;

import dev.suprim.gateway.auth.KiroCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public final class JsonCredentialReader {

	private static final Logger log = LoggerFactory.getLogger(
			JsonCredentialReader.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	private JsonCredentialReader() {}

	public static Optional<KiroCredentials> read(Path path) {
		try {
			if (!Files.exists(path)) {
				log.warn("[Auth] JSON creds not found: {}", path);
				return Optional.empty();
			}
			JsonNode json = mapper.readTree(Files.readString(path));
			String clientId = textOrNull(json, "clientId");
			String clientSecret = textOrNull(json, "clientSecret");

			return Optional.of(
					KiroCredentials.builder()
					               .accessToken(
							               textOrNull(
									               json,
									               "accessToken"
							               )
					               )
					               .refreshToken(
							               textOrNull(
									               json,
									               "refreshToken"
							               )
					               )
					               .expiresAt(
							               json.has("expiresAt") ? Instant.parse(
									               json.get("expiresAt")
									                   .asString()) : null
					               )
					               .profileArn(
							               textOrNull(
									               json,
									               "profileArn"
							               )
					               )
					               .clientId(clientId)
					               .clientSecret(clientSecret)
					               .authType(
							               detectAuthType(
									               clientId,
									               clientSecret
							               )
					               )
					               .build()
			);
		} catch (Exception e) {
			log.error("[Auth] Failed to load JSON creds: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static KiroCredentials.AuthType detectAuthType(
			String clientId,
			String clientSecret
	) {
		return (clientId != null && clientSecret != null)
				? KiroCredentials.AuthType.AWS_SSO_OIDC
				: KiroCredentials.AuthType.KIRO_DESKTOP;
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull()
				? node.get(field).asString() : null;
	}
}
