package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads an account's usage limits from {@code getUsageLimits}, which doubles as the only endpoint
 * that reports the account email.
 * <p>
 * Failures come back as {@code {"error": "..."}} rather than an exception: the caller renders this
 * in the accounts view, where one account's quota being unreadable must not fail the page.
 */
@Slf4j
final class KiroUsageLimits {

	private KiroUsageLimits() {}

	/**
	 * The raw usage-limits body, or a single-entry error map. A 403 is retried once with a fresh
	 * token via {@code refreshedToken}, unless the upstream said the feature is unsupported —
	 * that 403 is an answer, not an expiry.
	 */
	static Map<String, Object> fetch(
			StoredAccount account,
			String token,
			String region,
			ProxyChain proxyChain,
			Supplier<String> refreshedToken
	) {
		try {
			boolean isApiKey = "api_key".equals(account.authType());
			HttpRequest request = buildRequest(
					url(account, region, isApiKey),
					token,
					isApiKey
			);
			HttpResponse<String> response = proxyChain.send(request);

			if (shouldRetry(response, isApiKey)) {
				String retryToken = refreshedToken.get();
				if (retryToken != null) {
					response = proxyChain.send(
							KiroHeaders.reauthorized(request, retryToken)
					);
				}
			}
			if (response.statusCode() != 200) {
				return Map.of("error", KiroErrors.message(response));
			}
			return new JsonMapper().readValue(
					response.body(),
					new TypeReference<>() {}
			);
		} catch (Exception e) {
			String message = Optional.ofNullable(e.getMessage())
			                         .orElse("Unknown error");
			log.warn("[Usage] getUsageLimits failed: {}", message);
			return Map.of("error", message);
		}
	}

	/**
	 * The email attached to an API key, or null when the upstream does not report one. Uses the
	 * same endpoint as {@link #fetch} against the default region, since a bare key carries no
	 * region of its own.
	 */
	static String fetchEmail(String apiKey, ProxyChain proxyChain) {
		try {
			HttpResponse<String> response = proxyChain.send(
					buildRequest(
							Kiro.CODEWHISPERER_HOST + Kiro.USAGE_LIMITS_PATH,
							apiKey,
							true
					)
			);
			if (response.statusCode() != 200) {
				return null;
			}
			Map<String, Object> body = new JsonMapper().readValue(
					response.body(),
					new TypeReference<>() {}
			);
			if (body.get("userInfo") instanceof Map<?, ?> userInfo &&
			    userInfo.get("email") instanceof String email &&
			    !email.isBlank()
			) {
				return email;
			}
		} catch (Exception e) {
			log.warn(
					LogTag.KIRO + "fetchEmailForApiKey failed: {}",
					e.getMessage()
			);
		}
		return null;
	}

	/**
	 * A 403 normally means the token expired, except when the body names the feature as
	 * unsupported — that is a permanent answer for the account's plan.
	 */
	private static boolean shouldRetry(
			HttpResponse<String> response,
			boolean isApiKey
	) {
		return response.statusCode() == 403 && !isApiKey &&
		       !response.body().contains("FEATURE_NOT_SUPPORTED");
	}

	private static HttpRequest buildRequest(
			String url,
			String token,
			boolean isApiKey
	) {
		return KiroHeaders.apply(
				HttpRequest.newBuilder().uri(URI.create(url)).GET(),
				token,
				isApiKey
		).build();
	}

	/**
	 * SSO accounts scope the call to their profile; an API key is already scoped by its key.
	 */
	private static String url(
			StoredAccount account,
			String region,
			boolean isApiKey
	) {
		String url = Kiro.qHost(region) + Kiro.USAGE_LIMITS_PATH;
		if (!isApiKey && account.profileArn() != null) {
			url += "&profileArn=" + URLEncoder.encode(
					account.profileArn(),
					StandardCharsets.UTF_8
			);
		}
		return url;
	}
}
