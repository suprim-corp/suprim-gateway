package dev.suprim.gateway.auth.refresher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

/** Refreshes access token via Kiro Desktop social auth endpoint. */
public final class DesktopTokenRefresher {

	private static final ObjectMapper mapper = new ObjectMapper();

	private DesktopTokenRefresher() {}

	public static RefreshResult refresh(
			String refreshToken,
			String region,
			HttpClient httpClient
	) throws Exception {
		String url = "https://prod." + region +
		             ".auth.desktop.kiro.dev/refreshToken";
		String body = mapper.writeValueAsString(
				Map.of(
						"refreshToken",
						refreshToken
				)
		);

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(
				                                 HttpRequest.BodyPublishers.ofString(
						                                 body
				                                 )
		                                 )
		                                 .build();

		HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			String responseBody = response.body();
			throw new RuntimeException(
					"Desktop refresh failed (" + response.statusCode() + "): " +
					responseBody.substring(
							0,
							Math.min(300, responseBody.length())
					)
			);
		}

		JsonNode json = mapper.readTree(response.body());

		return RefreshResult.builder()
		                    .accessToken(textOrNull(json, "accessToken"))
		                    .refreshToken(textOrNull(json, "refreshToken"))
		                    .expiresAt(json.has("expiresAt") ? Instant.parse(json.get("expiresAt").asString()) : null)
		                    .profileArn(textOrNull(json, "profileArn"))
		                    .build();
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull()
				? node.get(field).asString() : null;
	}
}
