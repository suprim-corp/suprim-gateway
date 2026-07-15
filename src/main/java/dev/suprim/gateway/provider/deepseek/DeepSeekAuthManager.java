package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.StoredAccount;

import okhttp3.Request;
import okhttp3.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages authentication tokens for DeepSeek Web accounts.
 * Logs in via email/password, caches tokens per account, invalidates on demand.
 */
public class DeepSeekAuthManager {

	private final DeepSeekHttpClient httpClient;
	private final String baseUrl;
	private final ConcurrentHashMap<String, String> tokenCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> deviceIds = new ConcurrentHashMap<>();
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public DeepSeekAuthManager(DeepSeekHttpClient httpClient, String baseUrl) {
		this.httpClient = httpClient;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(
				0,
				baseUrl.length() - 1
		) : baseUrl;
	}

	public String getToken(StoredAccount account) throws IOException {
		String key = account.name();
		String cached = tokenCache.get(key);
		if (cached != null) {
			return cached;
		}
		String token = login(account);
		tokenCache.put(key, token);
		return token;
	}

	public void invalidateToken(StoredAccount account) {
		tokenCache.remove(account.name());
	}

	private String login(StoredAccount account) throws IOException {
		ObjectNode body = jsonMapper.createObjectNode();
		body.put("password", account.accessToken());
		body.put("device_id", "deepseek_to_api");
		body.put("os", "android");
		body.put("email", account.name());

		Request request = httpClient.buildPostRequest(
				baseUrl + "/api/v0/users/login",
				body.toString(),
				null,
				null
		);

		try (Response response = httpClient.execute(request)) {
			if (!response.isSuccessful()) {
				throw new IOException(
						"DeepSeek login failed: HTTP " + response.code()
				);
			}
			if (response.body() == null) {
				throw new IOException("DeepSeek login returned empty body");
			}
			String responseBody = response.body().string();
			JsonNode root = jsonMapper.readTree(responseBody);
			JsonNode tokenNode = root.path("data")
			                         .path("biz_data")
			                         .path("user")
			                         .path("token");
			if (tokenNode.isMissingNode() || tokenNode.asString().isEmpty()) {
				throw new IOException("DeepSeek login response missing token");
			}
			return tokenNode.asString();
		}
	}
}
