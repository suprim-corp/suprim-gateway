package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.instants.Xai;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Slf4j
class XaiTokenRefresher {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	static XaiTokenResponse exchangeCode(
			String code,
			String codeVerifier,
			String redirectUri
	) throws IOException {
		String body = "grant_type=authorization_code"
		              + "&client_id=" + encode(Xai.CLIENT_ID)
		              + "&code=" + encode(code)
		              + "&redirect_uri=" + encode(redirectUri)
		              + "&code_verifier=" + encode(codeVerifier);

		return postToken(body);
	}

	static XaiTokenResponse refresh(String refreshToken) throws IOException {
		String body = "grant_type=refresh_token"
		              + "&client_id=" + encode(Xai.CLIENT_ID)
		              + "&refresh_token=" + encode(refreshToken);

		return postToken(body);
	}

	static String decodeIdTokenEmail(String idToken) {
		if (idToken == null) {
			return null;
		}
		String[] parts = idToken.split("\\.");
		if (parts.length != 3) {
			return null;
		}
		try {
			String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
			JsonNode json = MAPPER.readTree(payload);
			if (json.has("email")) {
				return json.get("email").asString();
			}
			if (json.has("preferred_username")) {
				return json.get("preferred_username").asString();
			}
			if (json.has("sub")) {
				return json.get("sub").asString();
			}
			return null;
		} catch (Exception e) {
			log.warn("[xAI] Failed to decode id_token: {}", e.getMessage());
			return null;
		}
	}

	private static XaiTokenResponse postToken(String body) throws IOException {
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(Xai.TOKEN_URL))
		                                 .header("Content-Type", "application/x-www-form-urlencoded")
		                                 .POST(HttpRequest.BodyPublishers.ofString(body))
		                                 .build();
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IOException("xAI token request failed: " + response.statusCode() + " " + response.body());
			}
			JsonNode json = MAPPER.readTree(response.body());
			return new XaiTokenResponse(
					json.get("access_token").asString(),
					json.has("refresh_token") ? json.get("refresh_token").asString() : null,
					json.has("id_token") ? json.get("id_token").asString() : null,
					json.get("expires_in").asInt()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("xAI token request interrupted", e);
		}
	}

	private static String padBase64(String input) {
		int remainder = input.length() % 4;
		if (remainder == 2) return input + "==";
		if (remainder == 3) return input + "=";
		return input;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
