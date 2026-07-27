package dev.suprim.gateway.provider.antigravity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Headers for the {@code cloudcode-pa.googleapis.com} control-plane calls
 * ({@code loadCodeAssist}, {@code fetchAvailableModels}, {@code retrieveUserQuotaSummary}).
 * The streaming endpoint uses its own lighter set, see
 * {@link AntigravityHttpClient#buildHeaders}.
 */
final class AntigravityHeaders {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final String USER_AGENT =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.0.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36";
	private static final String API_CLIENT = "google-cloud-sdk vscode/1.96.0";

	private AntigravityHeaders() {}

	static Map<String, String> forControlPlane(String accessToken) {
		return Map.of(
				"Authorization", "Bearer " + accessToken,
				"Content-Type", "application/json",
				"User-Agent", USER_AGENT,
				"X-Goog-Api-Client", API_CLIENT,
				"Client-Metadata", ClientMetadata.DEFAULT.toJson()
		);
	}

	/**
	 * Value of the {@code Client-Metadata} header. Related to but distinct from
	 * {@link LoadCodeAssist.Request.ClientMetadata}: it splits the OS into
	 * {@code osVersion}/{@code arch} and spells the platform {@code MACOS} rather than
	 * {@code DARWIN_ARM64}. Only the request body's {@code ideType} affects which tiers the
	 * backend reports; this header is descriptive.
	 */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ClientMetadata(
			String ideType,
			String platform,
			String pluginType,
			String osVersion,
			String arch
	) {

		static final ClientMetadata DEFAULT =
				ClientMetadata.builder()
				              .ideType("VSCODE")
				              .platform("MACOS")
				              .pluginType("GEMINI")
				              .osVersion("15.1")
				              .arch("arm64")
				              .build();

		String toJson() {
			return MAPPER.writeValueAsString(this);
		}
	}
}
