package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Calls {@code ListAvailableModels} for one account and parses the result.
 * <p>
 * Unlike the usage-limits call, a failure here throws: an empty model list would look like a valid
 * "this account has nothing", which the caller cannot tell apart from a lookup that broke.
 */
final class KiroModelsClient {

	private KiroModelsClient() {}

	/**
	 * The account's exposed models, with hidden and disabled ids removed. A 403 is retried once
	 * with a fresh token from {@code refreshedToken}.
	 *
	 * @throws IOException when the upstream does not answer 200
	 */
	static List<Map<String, Object>> fetch(
			StoredAccount account,
			String configuredRegion,
			Set<String> disabledModels,
			ProxyChain proxyChain,
			Supplier<String> refreshedToken
	) throws IOException, InterruptedException {
		boolean isApiKey = "api_key".equals(account.authType());
		HttpRequest request = KiroHeaders.apply(
				HttpRequest.newBuilder()
				           .uri(URI.create(url(
						           account,
						           configuredRegion,
						           isApiKey
				           )))
				           .GET(),
				account.accessToken(),
				isApiKey
		).build();

		HttpResponse<String> response = proxyChain.send(request);
		if (response.statusCode() == 403 && !isApiKey) {
			String retryToken = refreshedToken.get();
			if (retryToken != null) {
				response = proxyChain.send(
						KiroHeaders.reauthorized(request, retryToken)
				);
			}
		}
		if (response.statusCode() != 200) {
			throw new IOException(KiroErrors.message(response));
		}
		return KiroModelListing.parse(
				upstreamModels(response.body()),
				disabledModels
		);
	}

	private static List<Map<String, Object>> upstreamModels(String body) {
		return Optional.ofNullable(
				new JsonMapper().readValue(body, ModelsResponse.class).models()
		).orElse(List.of());
	}

	/**
	 * SSO accounts scope the listing to their profile; an API key is already scoped by its key.
	 */
	private static String url(
			StoredAccount account,
			String configuredRegion,
			boolean isApiKey
	) {
		String url = KiroAccountRegion.modelsUrl(account, configuredRegion);
		if (!isApiKey && account.profileArn() != null) {
			url += "&profileArn=" + URLEncoder.encode(
					account.profileArn(),
					StandardCharsets.UTF_8
			);
		}
		return url;
	}

	private record ModelsResponse(List<Map<String, Object>> models) {}
}
