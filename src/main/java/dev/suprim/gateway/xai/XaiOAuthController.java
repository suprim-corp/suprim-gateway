package dev.suprim.gateway.xai;

import dev.suprim.gateway.instants.Xai;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Slf4j
@Controller
@RequiredArgsConstructor
public class XaiOAuthController {

	private final XaiAuthManager authManager;
	private final XaiLoopbackServer loopbackServer;

	@GetMapping("/auth/xai")
	String initiateOAuth(HttpServletRequest httpReq) {
		String codeVerifier = generateCodeVerifier();
		String codeChallenge = generateCodeChallenge(codeVerifier);
		String state = generateRandom(32);
		String nonce = generateRandom(32);

		String gatewayBase = buildGatewayBase(httpReq);
		loopbackServer.start(codeVerifier, state, gatewayBase, (code) -> {
			try {
				XaiTokenResponse tokenResponse = XaiTokenRefresher.exchangeCode(code, codeVerifier, Xai.REDIRECT_URI);
				String email = XaiTokenRefresher.decodeIdTokenEmail(tokenResponse.idToken());
				Instant expiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn());
				authManager.saveCredentials(tokenResponse.accessToken(), tokenResponse.refreshToken(), expiresAt, email);
				log.info("[xAI] OAuth complete, email={}", email);
			} catch (Exception e) {
				log.error("[xAI] Token exchange failed: {}", e.getMessage());
				throw new RuntimeException(e);
			}
		});

		String url = Xai.AUTH_URL
		             + "?response_type=code"
		             + "&client_id=" + encode(Xai.CLIENT_ID)
		             + "&redirect_uri=" + encode(Xai.REDIRECT_URI)
		             + "&scope=" + encode(Xai.SCOPE)
		             + "&code_challenge=" + encode(codeChallenge)
		             + "&code_challenge_method=S256"
		             + "&state=" + encode(state)
		             + "&nonce=" + encode(nonce)
		             + "&plan=generic"
		             + "&referrer=cli-proxy-api";

		return "redirect:" + url;
	}

	private static String generateCodeVerifier() {
		byte[] bytes = new byte[96];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String generateCodeChallenge(String codeVerifier) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	private static String generateRandom(int byteLength) {
		byte[] bytes = new byte[byteLength];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String buildGatewayBase(HttpServletRequest request) {
		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(scheme) && port == 80) ||
		                      ("https".equals(scheme) && port == 443);
		return scheme + "://" + host + (defaultPort ? "" : ":" + port);
	}
}
