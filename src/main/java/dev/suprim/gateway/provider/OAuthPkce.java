package dev.suprim.gateway.provider;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE and redirect helpers shared by the provider OAuth controllers.
 * <p>
 * Every provider runs the same authorization-code-with-PKCE flow, so the
 * verifier, challenge, state and gateway-base logic lives here rather than
 * being copied per provider — a divergence in any of them is a security bug,
 * not a style difference.
 */
public final class OAuthPkce {

	/**
	 * Verifier length in bytes. RFC 7636 allows 43–128 characters after
	 * base64url encoding; 96 bytes lands at 128, the maximum entropy the
	 * spec permits.
	 */
	private static final int VERIFIER_BYTES = 96;
	private static final int STATE_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER =
			Base64.getUrlEncoder().withoutPadding();

	private OAuthPkce() {}

	public static String codeVerifier() {
		return randomToken(VERIFIER_BYTES);
	}

	/**
	 * Verifier with an explicit entropy size, for providers already issuing a
	 * shorter one. Both lengths satisfy RFC 7636; this exists so adopting the
	 * shared helper does not silently change a live provider's verifier.
	 */
	public static String codeVerifier(int bytes) {
		return randomToken(bytes);
	}

	/** Opaque value for the {@code state} and {@code nonce} parameters. */
	public static String state() {
		return randomToken(STATE_BYTES);
	}

	public static String codeChallenge(String codeVerifier) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(
					codeVerifier.getBytes(StandardCharsets.US_ASCII)
			);
			return ENCODER.encodeToString(hash);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * The externally reachable origin of this gateway, used to build the URLs
	 * a browser or a helper script is told to come back to. Honours
	 * {@code X-Forwarded-*} so the flow still works behind a reverse proxy.
	 */
	public static String gatewayBase(HttpServletRequest request) {
		String forwardedProto = request.getHeader("X-Forwarded-Proto");
		String forwardedHost = request.getHeader("X-Forwarded-Host");

		if (forwardedProto != null && !forwardedProto.isEmpty()) {
			String host = (forwardedHost != null && !forwardedHost.isEmpty())
			              ? forwardedHost
			              : request.getServerName();
			return forwardedProto + "://" + host;
		}

		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(scheme) && port == 80) ||
		                      ("https".equals(scheme) && port == 443);
		if (!defaultPort) {
			host = host + ":" + port;
		}
		return scheme + "://" + host;
	}

	/** True when the request came from the machine running the gateway. */
	public static boolean isLoopback(HttpServletRequest request) {
		String host = request.getServerName();
		return "localhost".equals(host) || "127.0.0.1".equals(host);
	}

	private static String randomToken(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return ENCODER.encodeToString(buffer);
	}
}
