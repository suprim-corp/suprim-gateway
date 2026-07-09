package dev.suprim.gateway.auth.refresher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

// AWS SSO OIDC accepts camelCase keys (ref: Kiro-Go/auth/oidc.go)
public final class SsoOidcTokenRefresher {

	private static final ObjectMapper mapper = new ObjectMapper();

	private SsoOidcTokenRefresher() {}

	public static RefreshResult refresh(
			String refreshToken, String clientId, String clientSecret,
			String[] scopes, String region, HttpClient httpClient
	) throws Exception {
		String url = "https://oidc." + region + ".amazonaws.com/token";
		ObjectNode payload = mapper.createObjectNode();
		payload.put("grantType", "refresh_token");
		payload.put("clientId", clientId);
		payload.put("clientSecret", clientSecret);
		payload.put("refreshToken", refreshToken);
		if (scopes != null && scopes.length > 0) {
			ArrayNode arr = payload.putArray("scope");
			for (String s : scopes) arr.add(s);
		}

		HttpRequest.BodyPublisher bp = HttpRequest.BodyPublishers.ofString(
				mapper.writeValueAsString(payload)
		);

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(bp)
		                                 .build();

		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			String responseBody = response.body();
			String detail = responseBody.substring(
					0,
					Math.min(300, responseBody.length())
			);
			String msg = responseBody.contains("invalid_client")
					?
					"SSO OIDC client registration expired (~90 days). Re-open Kiro IDE to re-authorize, then restart gateway. Raw: " +
					detail
					: "SSO OIDC refresh failed (" + response.statusCode() +
					  "): " + detail;
			throw new RuntimeException(msg);
		}

		JsonNode json = mapper.readTree(response.body());
		String accessToken = coalesce(json, "accessToken", "access_token");
		String newRefreshToken = coalesce(
				json,
				"refreshToken",
				"refresh_token"
		);

		Instant expiresAt = Instant.now()
		                           .plusSeconds(
				                           intOrDefault(
						                           json,
						                           3600,
						                           "expiresIn",
						                           "expires_in"
				                           )
		                           );

		return RefreshResult.builder()
		                    .accessToken(accessToken)
		                    .refreshToken(newRefreshToken)
		                    .expiresAt(expiresAt)
		                    .build();
	}

	private static String coalesce(JsonNode node, String... fields) {
		for (String field : fields) {
			if (node.has(field) && !node.get(field).isNull()) {
				return node.get(field).asString();
			}
		}
		return null;
	}

	private static int intOrDefault(
			JsonNode node,
			int defaultValue,
			String... fields
	) {
		for (String field : fields) {
			if (node.has(field)) {
				return node.get(field).asInt();
			}
		}
		return defaultValue;
	}
}
