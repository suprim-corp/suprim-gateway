package dev.suprim.gateway.provider.kiro;

import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headers the CodeWhisperer and Q control-plane endpoints expect, identifying the caller as the
 * Kiro IDE's AWS SDK.
 * <p>
 * Every control-plane call sends the same set; the only variation is {@code tokentype}, which an
 * API-key account must send and an SSO one must not. Keeping them in one place means the SDK and
 * IDE version strings are stated once rather than per call site.
 */
final class KiroHeaders {

	private KiroHeaders() {}

	/**
	 * Applies the control-plane headers to {@code builder} and returns it, so it can be used
	 * inline in a request chain.
	 *
	 * @param isApiKey whether to send {@code tokentype: API_KEY}; SSO tokens are rejected when
	 *                 it is present
	 */
	static HttpRequest.Builder apply(
			HttpRequest.Builder builder,
			String token,
			boolean isApiKey
	) {
		forControlPlane(token, isApiKey).forEach(builder::header);
		return builder;
	}

	/**
	 * A copy of {@code request} carrying {@code token} instead of its original bearer, for
	 * retrying a call whose token expired.
	 * <p>
	 * {@code Authorization} is dropped while copying rather than overwritten afterwards:
	 * {@code HttpRequest.Builder#header} appends to a multi-valued header, so setting it on a
	 * copied request would send both tokens and the upstream would keep reading the stale one.
	 */
	static HttpRequest reauthorized(HttpRequest request, String token) {
		return HttpRequest.newBuilder(
				                  request,
				                  (name, value) -> !"Authorization".equalsIgnoreCase(name)
		                  )
		                  .header("Authorization", "Bearer " + token)
		                  .build();
	}

	/**
	 * The header set as a map, for callers that need to inspect or replay it.
	 */
	static Map<String, String> forControlPlane(String token, boolean isApiKey) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		headers.put("Accept", "application/json");
		headers.put("User-Agent", KiroUserAgent.controlPlane());
		headers.put("x-amz-user-agent", KiroUserAgent.amzControlPlane());
		headers.put("x-amzn-codewhisperer-optout", "true");
		if (isApiKey) {
			headers.put("tokentype", "API_KEY");
		}
		return headers;
	}
}
