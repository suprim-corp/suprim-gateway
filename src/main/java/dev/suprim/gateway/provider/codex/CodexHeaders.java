package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Request headers the ChatGPT backend expects from a Codex CLI client.
 * <p>
 * The real client sends {@code originator}, a structured User-Agent, and —
 * when signed in with a ChatGPT account — {@code ChatGPT-Account-Id} taken
 * from the {@code https://api.openai.com/auth} claim of the access token.
 * Workspace-scoped accounts are resolved by that header, so omitting it can
 * route a request to the wrong workspace or be rejected outright.
 */
@Slf4j
final class CodexHeaders {

	private static final JsonMapper MAPPER = new JsonMapper();
	private static final String AUTH_CLAIM = "https://api.openai.com/auth";
	private static final String USER_AGENT = buildUserAgent();

	private CodexHeaders() {}

	/**
	 * Applies the identifying headers every Codex backend call carries.
	 * {@code ChatGPT-Account-Id} is added only when the token actually
	 * declares an account, matching the CLI's API-key path which omits it.
	 */
	static void apply(HttpRequest.Builder builder, String accessToken) {
		builder.header("Authorization", "Bearer " + accessToken)
		       .header("originator", Codex.ORIGINATOR)
		       .header("User-Agent", USER_AGENT);
		accountId(accessToken).ifPresent(
				id -> builder.header("ChatGPT-Account-Id", id)
		);
	}

	/**
	 * Reads {@code chatgpt_account_id} out of the access token's auth claim.
	 * The token is a JWT the gateway already holds, so no request is needed.
	 */
	static Optional<String> accountId(String accessToken) {
		if (accessToken == null) {
			return Optional.empty();
		}
		String[] parts = accessToken.split("\\.");
		if (parts.length != 3) {
			return Optional.empty();
		}
		try {
			String payload = new String(
					Base64.getUrlDecoder().decode(padBase64(parts[1])),
					StandardCharsets.UTF_8
			);
			JsonNode auth = MAPPER.readTree(payload).get(AUTH_CLAIM);
			if (auth == null) {
				return Optional.empty();
			}
			return Optional.ofNullable(auth.get("chatgpt_account_id"))
			               .filter(JsonNode::isString)
			               .map(JsonNode::asString)
			               .filter(id -> !id.isEmpty());
		} catch (Exception e) {
			log.debug(
					"[Codex] Could not read account id from token: {}",
					e.getMessage()
			);
			return Optional.empty();
		}
	}

	/**
	 * Mirrors the CLI's User-Agent shape:
	 * {@code codex_cli_rs/<version> (<os> <release>; <arch>) <terminal>}.
	 * The gateway has no terminal, so it reports {@code unknown} — the same
	 * value the CLI uses when detection fails.
	 */
	private static String buildUserAgent() {
		String os = System.getProperty("os.name", "unknown")
		                  .toLowerCase(Locale.ROOT)
		                  .startsWith("mac")
				? "macos"
				: System.getProperty("os.name", "unknown")
				        .toLowerCase(Locale.ROOT);
		String release = System.getProperty("os.version", "unknown");
		String arch = System.getProperty("os.arch", "unknown");
		return "%s/%s (%s %s; %s) unknown".formatted(
				Codex.ORIGINATOR, Codex.CLIENT_VERSION, os, release, arch
		);
	}

	private static String padBase64(String input) {
		int remainder = input.length() % 4;
		if (remainder == 2) {
			return input + "==";
		}
		if (remainder == 3) {
			return input + "=";
		}
		return input;
	}
}
