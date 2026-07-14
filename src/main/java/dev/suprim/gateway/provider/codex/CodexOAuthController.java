package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;

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
public class CodexOAuthController {

	private final CodexAuthManager authManager;
	private final CodexLoopbackServer loopbackServer;

	private final ConcurrentHashMap<String, String> pendingVerifiers = new ConcurrentHashMap<>();

	@GetMapping("/auth/codex")
	String initiateOAuth(HttpServletRequest httpReq) {
		String host = httpReq.getServerName();
		boolean isLocal = "localhost".equals(host) || "127.0.0.1".equals(host);

		if (!isLocal) {
			String state = generateRandom();
			String codeVerifier = generateCodeVerifier();
			pendingVerifiers.put(state, codeVerifier);
			return "redirect:/auth/codex/remote?state=" + encode(state);
		}

		String codeVerifier = generateCodeVerifier();
		String codeChallenge = generateCodeChallenge(codeVerifier);
		String state = generateRandom();
		String nonce = generateRandom();

		String gatewayBase = buildGatewayBase(httpReq);
		loopbackServer.start(
				codeVerifier, state, gatewayBase, (code) -> {
					try {
						CodexTokenResponse tokenResponse = CodexTokenRefresher.exchangeCode(
								code,
								codeVerifier,
								Codex.REDIRECT_URI
						);
						String email = CodexTokenRefresher.decodeIdTokenEmail(
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
						log.info("[Codex] OAuth complete (local), email={}", email);
					} catch (Exception e) {
						log.error("[Codex] Token exchange failed: {}", e.getMessage());
						throw new RuntimeException(e);
					}
				}
		);

		String url = Codex.AUTH_URL
		             + "?response_type=code"
		             + "&client_id=" + encode(Codex.CLIENT_ID)
		             + "&redirect_uri=" + encode(Codex.REDIRECT_URI)
		             + "&scope=" + encode(Codex.SCOPE)
		             + "&code_challenge=" + encode(codeChallenge)
		             + "&code_challenge_method=S256"
		             + "&state=" + encode(state)
		             + "&nonce=" + encode(nonce)
		             + "&id_token_add_organizations=true"
		             + "&codex_cli_simplified_flow=true"
		             + "&originator=codex_cli_rs";

		return "redirect:" + url;
	}

	@GetMapping("/auth/codex/remote")
	@ResponseBody
	String remotePage(String state, HttpServletRequest httpReq) {
		String gatewayBase = buildGatewayBase(httpReq);
		return """
				<!DOCTYPE html>
				<html><head><title>Codex Connect</title>
				<style>body{font-family:system-ui;background:#0a0a0a;color:#e4e4e7;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
				.card{text-align:center;padding:2rem;border:1px solid #27272a;border-radius:8px;background:#18181b;max-width:600px}
				h1{font-size:1.25rem;margin:0 0 1rem}p{color:#a1a1aa;font-size:0.875rem;margin:0 0 1rem}
				pre{background:#09090b;border:1px solid #27272a;border-radius:4px;padding:1rem;text-align:left;font-size:0.75rem;overflow-x:auto;color:#a1a1aa}
				code{color:#3B82F6}</style></head>
				<body><div class="card">
				<h1>Codex OAuth (Remote Setup)</h1>
				<p>OpenAI requires localhost callback. Run this on your local machine:</p>
				<pre><code>curl -sL "%s/auth/codex/agent?state=%s" | bash</code></pre>
				<p>After login, token will be sent back to this gateway automatically.</p>
				</div></body></html>
				""".formatted(gatewayBase, state);
	}

	@GetMapping(value = "/auth/codex/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(String state, HttpServletRequest httpReq) {
		String codeVerifier = pendingVerifiers.get(state);
		if (codeVerifier == null) {
			return "echo 'Error: invalid or expired state'";
		}
		String gatewayBase = buildGatewayBase(httpReq);
		String codeChallenge = generateCodeChallenge(codeVerifier);

		String authBaseUrl = Codex.AUTH_URL
		                     + "?response_type=code"
		                     + "&client_id=" + encode(Codex.CLIENT_ID)
		                     + "&redirect_uri=" + encode(Codex.REDIRECT_URI)
		                     + "&scope=" + encode(Codex.SCOPE)
		                     + "&code_challenge_method=S256"
		                     + "&id_token_add_organizations=true"
		                     + "&codex_cli_simplified_flow=true"
		                     + "&originator=codex_cli_rs";

		return "#!/bin/bash\n"
		       + "GATEWAY='" + gatewayBase + "'\n"
		       + "STATE='" + state + "'\n"
		       + "CODE_VERIFIER='" + codeVerifier + "'\n"
		       + "CODE_CHALLENGE='" + codeChallenge + "'\n"
		       + "PORT=1455\n"
		       + "\n"
		       + "AUTH_URL=\"" + authBaseUrl + "&code_challenge=${CODE_CHALLENGE}&state=${STATE}\"\n"
		       + "\n"
		       + "open_url() {\n"
		       + "  if [ \"$(uname)\" = 'Darwin' ]; then\n"
		       + "    BROWSERS=()\n"
		       + "    for app in /Applications/*.app; do\n"
		       + "      if plutil -extract CFBundleURLTypes json -o - \"$app/Contents/Info.plist\" 2>/dev/null | grep -q '\"https\"'; then\n"
		       + "        if plutil -extract CFBundleDocumentTypes json -o - \"$app/Contents/Info.plist\" 2>/dev/null | grep -qi 'html'; then\n"
		       + "          BROWSERS+=(\"$(basename \"$app\" .app)\")\n"
		       + "        fi\n"
		       + "      fi\n"
		       + "    done\n"
		       + "    if [ ${#BROWSERS[@]} -eq 0 ]; then\n"
		       + "      open \"$1\"\n"
		       + "    else\n"
		       + "      echo 'Select browser:'\n"
		       + "      for i in \"${!BROWSERS[@]}\"; do echo \"  $((i+1))) ${BROWSERS[$i]}\"; done\n"
		       + "      echo \"  $((${#BROWSERS[@]}+1))) Default\"\n"
		       + "      printf 'Choice [%d]: ' $((${#BROWSERS[@]}+1))\n"
		       + "      read -r PICK </dev/tty\n"
		       + "      PICK=${PICK:-$((${#BROWSERS[@]}+1))}\n"
		       + "      if [ \"$PICK\" -gt ${#BROWSERS[@]} ] 2>/dev/null; then\n"
		       + "        open \"$1\"\n"
		       + "      else\n"
		       + "        open -a \"${BROWSERS[$((PICK-1))]}\" \"$1\"\n"
		       + "      fi\n"
		       + "    fi\n"
		       + "  else\n"
		       + "    xdg-open \"$1\" 2>/dev/null || echo \"Open this URL: $1\"\n"
		       + "  fi\n"
		       + "}\n"
		       + "echo ''\n"
		       + "printf '\\033[1;34m  OpenAI Codex Authorization\\033[0m\\n'\n"
		       + "echo ''\n"
		       + "printf '  Open this URL in your browser to authorize:\\033[0m\\n'\n"
		       + "echo ''\n"
		       + "printf '  \\033[4;36m%s\\033[0m\\n' \"$AUTH_URL\"\n"
		       + "echo ''\n"
		       + "printf '  \\033[2mWaiting for callback on localhost:%s...\\033[0m\\n' $PORT\n"
		       + "echo ''\n"
		       + "open_url \"$AUTH_URL\"\n"
		       + "TMPFILE=$(mktemp)\n"
		       + "RESPONSE_BODY='<html><body><h3>Success! You can close this tab.</h3></body></html>'\n"
		       + "RESPONSE=\"HTTP/1.1 200 OK\\r\\nContent-Type: text/html\\r\\nConnection: close\\r\\nContent-Length: ${#RESPONSE_BODY}\\r\\n\\r\\n$RESPONSE_BODY\"\n"
		       + "{ printf '%b' \"$RESPONSE\"; } | nc -l $PORT > \"$TMPFILE\" 2>/dev/null || { printf '%b' \"$RESPONSE\"; } | nc -l -p $PORT > \"$TMPFILE\" 2>/dev/null\n"
		       + "REQUEST=$(cat \"$TMPFILE\")\n"
		       + "rm -f \"$TMPFILE\"\n"
		       + "CODE=$(echo \"$REQUEST\" | sed -n 's/.*code=\\([^& ]*\\).*/\\1/p' | head -1)\n"
		       + "\n"
		       + "if [ -z \"$CODE\" ]; then echo 'Error: no code received'; exit 1; fi\n"
		       + "echo 'Code received, exchanging tokens...'\n"
		       + "\n"
		       + "curl -sL -X POST \"$GATEWAY/auth/codex/exchange\" \\\n"
		       + "  -H 'Content-Type: application/json' \\\n"
		       + "  -d '{\"code\":\"'\"$CODE\"'\",\"state\":\"'\"$STATE\"'\"}'\n"
		       + "echo ''\n"
		       + "echo 'Done! Codex account connected.'\n";
	}

	@PostMapping("/auth/codex/state")
	@ResponseBody
	Map<String, String> generateState() {
		String codeVerifier = generateCodeVerifier();
		String state = generateRandom();
		pendingVerifiers.put(state, codeVerifier);
		return Map.of("state", state);
	}

	@PostMapping("/auth/codex/exchange")
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
			CodexTokenResponse tokenResponse = CodexTokenRefresher.exchangeCode(
					code,
					codeVerifier,
					Codex.REDIRECT_URI
			);
			String email = CodexTokenRefresher.decodeIdTokenEmail(tokenResponse.idToken());
			Instant expiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn());
			authManager.saveCredentials(
					tokenResponse.accessToken(),
					tokenResponse.refreshToken(),
					expiresAt,
					email
			);
			log.info("[Codex] OAuth complete (remote), email={}", email);
			return Map.of("status", "ok", "email", email != null ? email : "");
		} catch (Exception e) {
			log.error("[Codex] Remote token exchange failed: {}", e.getMessage());
			return Map.of("error", e.getMessage());
		}
	}

	@PostMapping("/auth/codex/device-exchange")
	@ResponseBody
	Map<String, String> deviceExchange(@RequestBody Map<String, Object> body) {
		String accessToken = (String) body.get("access_token");
		String refreshToken = (String) body.get("refresh_token");
		String idToken = (String) body.get("id_token");
		Object expiresInObj = body.get("expires_in");

		if (accessToken == null) {
			return Map.of("error", "missing access_token");
		}

		int expiresIn = expiresInObj instanceof Number n ? n.intValue() : 3600;
		String email = CodexTokenRefresher.decodeIdTokenEmail(idToken);
		Instant expiresAt = Instant.now().plusSeconds(expiresIn);
		authManager.saveCredentials(accessToken, refreshToken, expiresAt, email);
		log.info("[Codex] OAuth complete (device flow), email={}", email);
		return Map.of("status", "ok", "email", email != null ? email : "");
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
