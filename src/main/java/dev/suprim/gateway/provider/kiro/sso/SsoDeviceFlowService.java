package dev.suprim.gateway.provider.kiro.sso;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.proxy.ProxyChain;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public final class SsoDeviceFlowService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CLIENT_NAME = "kiro-gateway";
	private static final String[] SCOPES = {
			"codewhisperer:completions",
			"codewhisperer:analysis",
			"codewhisperer:conversations",
			"codewhisperer:transformations",
			"codewhisperer:taskassist"
	};

	private SsoDeviceFlowService() {}

	public static Map<String, String> registerClient(
			String startUrl,
			String region,
			ProxyChain proxyChain
	) throws Exception {
		String url =
				"https://oidc." + region + ".amazonaws.com/client/register";

		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("clientName", CLIENT_NAME);
		payload.put("clientType", "public");
		ArrayNode scopesArray = payload.putArray("scopes");
		for (String scope : SCOPES) {
			scopesArray.add(scope);
		}
		ArrayNode grantTypes = payload.putArray("grantTypes");
		grantTypes.add("urn:ietf:params:oauth:grant-type:device_code");
		grantTypes.add("refresh_token");
		payload.put("issuerUrl", startUrl);

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(HttpRequest.BodyPublishers.ofString(
				                                 MAPPER.writeValueAsString(
						                                 payload)))
		                                 .build();

		HttpResponse<String> response;

		try {
			response = proxyChain.send(request);
		} catch (Exception e) {
			throw new RuntimeException(
					"RegisterClient request failed: " + e.getMessage(),
					e
			);
		}

		if (response.statusCode() != 200) {
			throw new RuntimeException(
					"RegisterClient failed (" + response.statusCode() + "): " +
					response.body()
					        .substring(
							        0,
							        Math.min(300, response.body().length())
					        ));
		}

		JsonNode json = MAPPER.readTree(response.body());
		return Map.of(
				"clientId", json.get("clientId").asString(),
				"clientSecret", json.get("clientSecret").asString()
		);
	}

	public static Map<String, Object> startDeviceAuthorization(
			String clientId, String clientSecret, String startUrl,
			String region, ProxyChain proxyChain
	) throws Exception {
		String url = "https://oidc." + region +
		             ".amazonaws.com/device_authorization";

		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("clientId", clientId);
		payload.put("clientSecret", clientSecret);
		payload.put("startUrl", startUrl);

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(HttpRequest.BodyPublishers.ofString(
				                                 MAPPER.writeValueAsString(
						                                 payload)))
		                                 .build();

		HttpResponse<String> response = proxyChain.send(request);
		if (response.statusCode() != 200) {
			throw new RuntimeException("StartDeviceAuthorization failed (" +
			                           response.statusCode() + "): " +
			                           response.body()
			                                   .substring(
					                                   0,
					                                   Math.min(
							                                   300,
							                                   response.body()
							                                           .length()
					                                   )
			                                   ));
		}

		JsonNode json = MAPPER.readTree(response.body());
		return Map.of(
				"deviceCode",
				json.get("deviceCode").asString(),
				"userCode",
				json.get("userCode").asString(),
				"verificationUri",
				json.get("verificationUri").asString(),
				"verificationUriComplete",
				json.has("verificationUriComplete") ? json.get(
						"verificationUriComplete").asString() : "",
				"interval",
				json.has("interval") ? json.get("interval").asInt() : 5,
				"expiresIn",
				json.has("expiresIn") ? json.get("expiresIn").asInt() : 600
		);
	}

	public static Map<String, Object> createToken(
			String clientId, String clientSecret, String deviceCode,
			String region, ProxyChain proxyChain
	) throws Exception {
		String url = "https://oidc." + region + ".amazonaws.com/token";

		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("clientId", clientId);
		payload.put("clientSecret", clientSecret);
		payload.put("deviceCode", deviceCode);
		payload.put(
				"grantType",
				"urn:ietf:params:oauth:grant-type:device_code"
		);

		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create(url))
		                                 .header(
				                                 "Content-Type",
				                                 "application/json"
		                                 )
		                                 .POST(HttpRequest.BodyPublishers.ofString(
				                                 MAPPER.writeValueAsString(
						                                 payload)))
		                                 .build();

		HttpResponse<String> response = proxyChain.send(request);

		if (response.statusCode() == 400) {
			JsonNode errorJson = MAPPER.readTree(response.body());
			String error = errorJson.has("error") ? errorJson.get("error")
			                                                 .asString() : "";
			String errorDescription = errorJson.has("error_description") ? errorJson.get(
					"error_description").asString() : "";
			if ("authorization_pending".equals(error)) {
				return Map.of("status", "pending");
			}
			if ("slow_down".equals(error)) {
				return Map.of("status", "slow_down");
			}
			if ("expired_token".equals(error)) {
				return Map.of("status", "expired");
			}
			if ("access_denied".equals(error)) {
				return Map.of("status", "denied");
			}
			throw new RuntimeException(
					"CreateToken failed: " + error + " — " + errorDescription);
		}

		if (response.statusCode() != 200) {
			throw new RuntimeException(
					"CreateToken failed (" + response.statusCode() + "): " +
					response.body()
					        .substring(
							        0,
							        Math.min(300, response.body().length())
					        )
			);
		}

		JsonNode json = MAPPER.readTree(response.body());
		String accessToken = json.has("accessToken") ? json.get("accessToken")
		                                                   .asString() : null;
		String refreshToken = json.has("refreshToken") ? json.get("refreshToken")
		                                                     .asString() : null;
		int expiresIn = json.has("expiresIn") ? json.get("expiresIn")
		                                            .asInt() : 3600;

		return Map.of(
				"status", "ok",
				"accessToken", accessToken != null ? accessToken : "",
				"refreshToken", refreshToken != null ? refreshToken : "",
				"expiresIn", expiresIn
		);
	}

	public static String fetchEmail(
			String accessToken,
			String region,
			ProxyChain proxyChain
	) {
		try {
			String url = String.format(Kiro.Q_HOST_TEMPLATE, region) +
			             Kiro.USAGE_LIMITS_PATH;
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(url))
			                                 .header(
					                                 "Authorization",
					                                 "Bearer " + accessToken
			                                 )
			                                 .header(
					                                 "Accept",
					                                 "application/json"
			                                 )
			                                 .GET()
			                                 .build();
			HttpResponse<String> response = proxyChain.send(request);
			if (response.statusCode() == 200) {
				JsonNode root = MAPPER.readTree(response.body());
				JsonNode email = root.path("userInfo").path("email");
				if (!email.isMissingNode() && !email.isNull()) {
					return email.asString();
				}
			}
		} catch (Exception ignored) {}
		return null;
	}
}
