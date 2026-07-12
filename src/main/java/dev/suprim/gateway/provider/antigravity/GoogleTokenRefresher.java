package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class GoogleTokenRefresher {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	public static GoogleTokenResponse refresh(
			String refreshToken,
			String clientId,
			String clientSecret
	) throws IOException {
		String body = "grant_type=refresh_token"
		              + "&refresh_token=" + refreshToken
		              + "&client_id=" + clientId
		              + "&client_secret=" + clientSecret;

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(Antigravity.GOOGLE_TOKEN_URL))
		                                 .header(
				                                 "Content-Type",
				                                 "application/x-www-form-urlencoded"
		                                 )
		                                 .POST(
				                                 HttpRequest.BodyPublishers.ofString(
						                                 body
				                                 )
		                                 )
		                                 .build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				throw new IOException(
						"Token refresh failed: " + response.statusCode() + " " +
						response.body());
			}
			JsonNode json = MAPPER.readTree(response.body());
			return new GoogleTokenResponse(
					json.get("access_token").asString(),
					json.has("refresh_token") ? json.get("refresh_token")
					                                .asString() : null,
					json.get("expires_in").asInt()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Token refresh interrupted", e);
		}
	}
}
