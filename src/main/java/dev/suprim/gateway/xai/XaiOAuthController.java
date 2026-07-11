package dev.suprim.gateway.xai;

import dev.suprim.gateway.instants.Xai;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Controller
@RequiredArgsConstructor
public class XaiOAuthController {

	private final XaiAuthManager authManager;
	private final XaiLoopbackServer loopbackServer;

	private final ConcurrentHashMap<String, String> pendingVerifiers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> pendingAuthUrls = new ConcurrentHashMap<>();

	@GetMapping("/auth/xai")
	String initiateOAuth(HttpServletRequest httpReq) {
		String host = httpReq.getServerName();
		boolean isLocal = "localhost".equals(host) || "127.0.0.1".equals(host);

		String codeVerifier = generateCodeVerifier();
		String codeChallenge = generateCodeChallenge(codeVerifier);
		String state = generateRandom();
		String nonce = generateRandom();

		if (isLocal) {
			String gatewayBase = buildGatewayBase(httpReq);
			loopbackServer.start(
					codeVerifier, state, gatewayBase, (code) -> {
						try {
							XaiTokenResponse tokenResponse = XaiTokenRefresher.exchangeCode(
									code,
									codeVerifier,
									Xai.REDIRECT_URI
							);
							String email = XaiTokenRefresher.decodeIdTokenEmail(
									tokenResponse.idToken()
							);
							Instant expiresAt = Instant.now().plusSeconds(
									tokenResponse.expiresIn()
							);
							authManager.saveCredentials(
									tokenResponse.accessToken(),
									tokenResponse.refreshToken(),
									expiresAt,
									email
							);
							log.info(
									"[xAI] OAuth complete (local), email={}",
									email
							);
						} catch (Exception e) {
							log.error(
									"[xAI] Token exchange failed: {}",
									e.getMessage()
							);
							throw new RuntimeException(e);
						}
					}
			);
		} else {
			pendingVerifiers.put(state, codeVerifier);
		}

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

		if (isLocal) {
			return "redirect:" + url;
		}

		pendingAuthUrls.put(state, url);
		return "redirect:/auth/xai/remote?state=" + encode(state);
	}

	@GetMapping("/auth/xai/remote")
	@ResponseBody
	String remotePage(String state, HttpServletRequest httpReq) {
		String gatewayBase = buildGatewayBase(httpReq);
		return """
				<!DOCTYPE html>
				<html><head><title>xAI Connect</title>
				<style>body{font-family:system-ui;background:#0a0a0a;color:#e4e4e7;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
				.card{text-align:center;padding:2rem;border:1px solid #27272a;border-radius:8px;background:#18181b;max-width:600px}
				h1{font-size:1.25rem;margin:0 0 1rem}p{color:#a1a1aa;font-size:0.875rem;margin:0 0 1rem}
				pre{background:#09090b;border:1px solid #27272a;border-radius:4px;padding:1rem;text-align:left;font-size:0.75rem;overflow-x:auto;color:#a1a1aa}
				code{color:#c084fc}</style></head>
				<body><div class="card">
				<h1>xAI OAuth (Remote Setup)</h1>
				<p>xAI requires localhost callback. Run this on your local machine:</p>
				<pre><code>curl -sL "%s/auth/xai/agent?state=%s" | bash</code></pre>
				<p>After login, token will be sent back to this gateway automatically.</p>
				</div></body></html>
				""".formatted(gatewayBase, state);
	}

	@GetMapping(value = "/auth/xai/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(String state, HttpServletRequest httpReq) {
		String codeVerifier = pendingVerifiers.get(state);
		String authUrl = pendingAuthUrls.get(state);
		if (codeVerifier == null || authUrl == null) {
			return "echo 'Error: invalid or expired state'";
		}
		String gatewayBase = buildGatewayBase(httpReq);

		return "#!/bin/bash\n"
		       + "GATEWAY='" + gatewayBase + "'\n"
		       + "STATE='" + state + "'\n"
		       + "echo 'Starting xAI OAuth agent...'\n"
		       + "echo 'Opening browser for authentication...'\n"
		       + "open '" + authUrl + "' 2>/dev/null || xdg-open '" + authUrl
		       + "' 2>/dev/null || echo 'Open this URL manually:'\n"
		       + "echo 'Waiting for callback on port 56121...'\n"
		       + "TMPFILE=$(mktemp)\n"
		       + "{ echo -ne 'HTTP/1.1 302 Found\\r\\nLocation: '$GATEWAY'/providers?xai=connected\\r\\nContent-Length: 0\\r\\n\\r\\n'; cat; } | nc -l 56121 > \"$TMPFILE\"\n"
		       + "CODE=$(grep -o 'code=[^& ]*' \"$TMPFILE\" | head -1 | cut -d= -f2)\n"
		       + "rm -f \"$TMPFILE\"\n"
		       + "if [ -z \"$CODE\" ]; then echo 'Error: no code received'; exit 1; fi\n"
		       + "echo \"Got code: ${CODE:0:10}...\"\n"
		       + "echo 'Sending to gateway...'\n"
		       + "curl -sL -X POST \"$GATEWAY/auth/xai/exchange\" \\\n"
		       + "  -H 'Content-Type: application/json' \\\n"
		       + "  -d '{\"code\":\"'\"$CODE\"'\",\"state\":\"'\"$STATE\"'\"}'\n"
		       + "echo ''\necho 'Done!'\n";
	}

	@PostMapping("/auth/xai/exchange")
	@ResponseBody
	Map<String, String> exchange(@RequestBody Map<String, String> body) {
		String code = body.get("code");
		String state = body.get("state");

		if (code == null || state == null) {
			return Map.of("error", "missing fields");
		}

		String codeVerifier = pendingVerifiers.remove(state);
		if (codeVerifier == null) {
			return Map.of("error", "invalid or expired state");
		}

		try {
			XaiTokenResponse tokenResponse = XaiTokenRefresher.exchangeCode(
					code,
					codeVerifier,
					Xai.REDIRECT_URI
			);
			String email = XaiTokenRefresher.decodeIdTokenEmail(tokenResponse.idToken());
			Instant expiresAt = Instant.now()
			                           .plusSeconds(tokenResponse.expiresIn());
			authManager.saveCredentials(
					tokenResponse.accessToken(),
					tokenResponse.refreshToken(),
					expiresAt,
					email
			);
			log.info("[xAI] OAuth complete (remote), email={}", email);
			return Map.of("status", "ok", "email", email != null ? email : "");
		} catch (Exception e) {
			log.error("[xAI] Remote token exchange failed: {}", e.getMessage());
			return Map.of("error", e.getMessage());
		}
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

	private static String generateRandom() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String buildGatewayBase(HttpServletRequest request) {
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
}
