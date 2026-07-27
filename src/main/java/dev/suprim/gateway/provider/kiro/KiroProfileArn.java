package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Resolves the CodeWhisperer profile ARN an account routes through, and reads the region back
 * out of one.
 * <p>
 * An account issued without a stored ARN has to ask the upstream for it, since the ARN is part of
 * every subsequent data-plane URL. Failures are logged and reported as null rather than thrown:
 * a missing ARN degrades routing to the default region instead of failing the account outright.
 */
@Slf4j
final class KiroProfileArn {

	private static final String PROFILES_PATH = "/ListAvailableProfiles";
	private static final String PROFILES_BODY = "{\"maxResults\":10}";

	private KiroProfileArn() {}

	/**
	 * The first profile ARN the token can see, or null when the lookup finds none.
	 */
	static String fetch(String token, boolean isApiKey, ProxyChain proxyChain) {
		try {
			HttpRequest.Builder builder =
					HttpRequest.newBuilder()
					           .uri(URI.create(
							           Kiro.CODEWHISPERER_HOST + PROFILES_PATH))
					           .header("Content-Type", "application/json");
			KiroHeaders.apply(builder, token, isApiKey);
			HttpResponse<String> response = proxyChain.send(
					builder.POST(HttpRequest.BodyPublishers.ofString(
							PROFILES_BODY)).build()
			);
			if (response.statusCode() != 200) {
				log.warn(
						LogTag.KIRO + "ListAvailableProfiles HTTP {}: {}",
						response.statusCode(),
						response.body()
				);
				return null;
			}
			return firstArn(response.body());
		} catch (Exception e) {
			log.warn(
					LogTag.KIRO + "fetchProfileArn failed: {}",
					e.getMessage()
			);
			return null;
		}
	}

	private static String firstArn(String body) {
		Map<String, Object> parsed = new JsonMapper().readValue(
				body,
				new TypeReference<>() {}
		);
		if (parsed.get("profiles") instanceof List<?> profiles &&
		    !profiles.isEmpty() &&
		    profiles.getFirst() instanceof Map<?, ?> profile &&
		    profile.get("arn") instanceof String arn && !arn.isBlank()) {
			return arn;
		}
		return null;
	}

	/**
	 * The region embedded in a CodeWhisperer ARN, or null when the value is not one. The ARN is
	 * the most reliable region source an account has, since it is issued by the region that
	 * actually serves the account.
	 */
	static String region(String profileArn) {
		if (profileArn == null || profileArn.isBlank()) {
			return null;
		}
		String[] parts = profileArn.split(":", 6);
		if (parts.length < 6 || !"arn".equals(parts[0]) ||
		    !"codewhisperer".equals(parts[2])) {
			return null;
		}
		String region = parts[3].trim();
		return region.isEmpty() ? null : region;
	}
}
