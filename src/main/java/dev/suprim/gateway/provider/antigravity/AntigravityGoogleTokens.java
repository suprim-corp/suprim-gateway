package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.provider.OAuthPkce;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Google token-endpoint and userinfo calls for the Antigravity OAuth flow.
 */
@Slf4j
final class AntigravityGoogleTokens {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	private AntigravityGoogleTokens() {}

	@Builder
	record Tokens(String accessToken, String refreshToken, int expiresIn) {}

	/** Trades an authorization code for tokens at Google's token endpoint. */
	static Tokens exchangeCode(String code, String codeVerifier) throws IOException {
		String body = "grant_type=authorization_code"
		              + "&code=" + OAuthPkce.encode(code)
		              + "&redirect_uri=" + OAuthPkce.encode(Antigravity.REDIRECT_URI)
		              + "&client_id=" + OAuthPkce.encode(Antigravity.CLIENT_ID)
		              + "&client_secret=" + OAuthPkce.encode(Antigravity.CLIENT_SECRET)
		              + "&code_verifier=" + OAuthPkce.encode(codeVerifier);

		HttpRequest request =
				HttpRequest.newBuilder()
				           .uri(URI.create(Antigravity.GOOGLE_TOKEN_URL))
				           .header(
						           "Content-Type",
						           "application/x-www-form-urlencoded"
				           )
				           .POST(HttpRequest.BodyPublishers.ofString(body))
				           .build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				throw new IOException(
						"Token exchange failed: " + response.statusCode() +
						" " + response.body()
				);
			}

			JsonNode json = MAPPER.readTree(response.body());
			return Tokens.builder()
			             .accessToken(json.get("access_token").asString())
			             .refreshToken(
					             json.has("refresh_token")
					             ? json.get("refresh_token").asString()
					             : null
			             )
			             .expiresIn(json.get("expires_in").asInt())
			             .build();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Token exchange interrupted", e);
		}
	}

	/**
	 * Reads the account's email so the UI can label it.
	 * Best-effort: a failure here should not fail an otherwise good login.
	 *
	 * @return the email, or null when it cannot be read
	 */
	static String fetchEmail(String accessToken) {
		try {
			HttpRequest request =
					HttpRequest.newBuilder()
					           .uri(URI.create(Antigravity.USERINFO_URL))
					           .header("Authorization", "Bearer " + accessToken)
					           .GET()
					           .build();
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() == 200) {
				JsonNode email = MAPPER.readTree(response.body()).get("email");
				return email != null ? email.asString() : null;
			}
		} catch (Exception e) {
			log.warn("[Antigravity] Failed to fetch email: {}", e.getMessage());
		}
		return null;
	}
}
