package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AntigravityOAuthController {

	private static final String REDIRECT_URI = Antigravity.REDIRECT_URI;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	private final AntigravityAuthManager authManager;
	private final AntigravityLoopbackServer loopbackServer;

	@GetMapping("/auth/antigravity")
	String initiateOAuth(HttpServletRequest httpReq) {
		String codeVerifier = generateCodeVerifier();
		String codeChallenge = generateCodeChallenge(codeVerifier);
		String gatewayBase = buildGatewayBase(httpReq);

		loopbackServer.start(
				codeVerifier, gatewayBase, (code) -> {
					try {
						exchangeCodeAndStore(code, codeVerifier);
						log.info("[Antigravity] OAuth complete via loopback");
					} catch (Exception e) {
						log.error(
								"[Antigravity] Token exchange failed: {}",
								e.getMessage()
						);
						throw new RuntimeException(e);
					}
				}
		);

		String url =
				Antigravity.GOOGLE_AUTH_URL + "?client_id=" +
				encode(Antigravity.CLIENT_ID)
				+ "&redirect_uri=" + encode(REDIRECT_URI)
				+ "&response_type=code"
				+ "&scope=" + encode(Antigravity.OAUTH_SCOPE).replace("+", "%20")
				+ "&code_challenge=" + encode(codeChallenge)
				+ "&code_challenge_method=S256"
				+ "&access_type=offline";

		return "redirect:" + url;
	}

	@GetMapping(value = "/auth/antigravity/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(HttpServletRequest httpReq) {
		String gatewayBase = buildGatewayBase(httpReq);

		String authBaseUrl = Antigravity.GOOGLE_AUTH_URL
		                     + "?client_id=" + encode(Antigravity.CLIENT_ID)
		                     + "&redirect_uri=" + encode(REDIRECT_URI)
		                     + "&response_type=code"
		                     + "&scope=" + encode(Antigravity.OAUTH_SCOPE)
		                     + "&code_challenge_method=S256"
		                     + "&access_type=offline"
		                     + "&prompt=consent";

		return "#!/bin/bash\n"
		       + "GATEWAY='" + gatewayBase + "'\n"
		       + "REDIRECT_URI='" + REDIRECT_URI + "'\n"
		       + "CLIENT_ID='" + Antigravity.CLIENT_ID + "'\n"
		       + "CLIENT_SECRET='" + Antigravity.CLIENT_SECRET + "'\n"
		       + "PORT=51121\n"
		       + "\n"
		       + "CODE_VERIFIER=$(openssl rand -hex 32)\n"
		       + "CODE_CHALLENGE=$(printf '%s' \"$CODE_VERIFIER\" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')\n"
		       + "STATE=$(openssl rand -hex 16)\n"
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
		       + "printf '\\033[1;35m  Antigravity OAuth\\033[0m\\n'\n"
		       + "echo ''\n"
		       + "printf '  Open this URL in your browser to authorize:\\033[0m\\n'\n"
		       + "echo ''\n"
		       + "printf '  \\033[4;36m%s\\033[0m\\n' \"$AUTH_URL\"\n"
		       + "echo ''\n"
		       + "printf '  \\033[2mWaiting for callback on localhost:%s...\\033[0m\\n' $PORT\n"
		       + "echo ''\n"
		       + "open_url \"$AUTH_URL\"\n"
		       + "TMPFILE=$(mktemp)\n"
		       + "RESPONSE=\"HTTP/1.1 302 Found\\r\\nLocation: $GATEWAY/oauth-success.html\\r\\nConnection: close\\r\\n\\r\\n\"\n"
		       + "{ printf '%b' \"$RESPONSE\"; } | nc -l $PORT > \"$TMPFILE\" 2>/dev/null || { printf '%b' \"$RESPONSE\"; } | nc -l -p $PORT > \"$TMPFILE\" 2>/dev/null\n"
		       + "REQUEST=$(cat \"$TMPFILE\")\n"
		       + "rm -f \"$TMPFILE\"\n"
		       + "CODE=$(echo \"$REQUEST\" | sed -n 's/.*code=\\([^& ]*\\).*/\\1/p' | head -1)\n"
		       + "\n"
		       + "if [ -z \"$CODE\" ]; then echo 'Error: no code received'; exit 1; fi\n"
		       + "echo 'Code received, exchanging for tokens...'\n"
		       + "\n"
		       + "TOKEN_RESP=$(curl -s -X POST 'https://oauth2.googleapis.com/token' \\\n"
		       + "  -H 'Content-Type: application/x-www-form-urlencoded' \\\n"
		       + "  -d \"grant_type=authorization_code&code=$CODE&redirect_uri=$REDIRECT_URI&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code_verifier=$CODE_VERIFIER\")\n"
		       + "\n"
		       + "if echo \"$TOKEN_RESP\" | grep -q '\"access_token\"'; then\n"
		       + "  echo 'Sending tokens to gateway...'\n"
		       + "  curl -sL -X POST \"$GATEWAY/auth/antigravity/token-exchange\" \\\n"
		       + "    -H 'Content-Type: application/json' \\\n"
		       + "    -d \"$TOKEN_RESP\"\n"
		       + "  echo ''\n"
		       + "  echo 'Done! Antigravity account connected.'\n"
		       + "else\n"
		       + "  echo \"Error: $TOKEN_RESP\"\n"
		       + "  exit 1\n"
		       + "fi\n";
	}

	@PostMapping("/auth/antigravity/token-exchange")
	@ResponseBody
	Map<String, String> tokenExchange(@RequestBody Map<String, Object> body) {
		String accessToken = (String) body.get("access_token");
		String refreshToken = (String) body.get("refresh_token");
		Object expiresInObj = body.get("expires_in");

		if (accessToken == null) {
			return Map.of("error", "missing access_token");
		}

		int expiresIn = expiresInObj instanceof Number n ? n.intValue() : 3600;
		Instant expiresAt = Instant.now().plusSeconds(expiresIn);

		try {
			String email = fetchEmail(accessToken);
			String projectId = ProjectIdFetcher.fetch(accessToken);
			authManager.saveCredentials(accessToken, refreshToken, expiresAt, projectId, email);
			log.info("[Antigravity] OAuth complete (remote), email={}, projectId={}", email, projectId);
			return Map.of("status", "ok", "email", email != null ? email : "");
		} catch (Exception e) {
			log.error("[Antigravity] Remote token exchange failed: {}", e.getMessage());
			return Map.of("error", e.getMessage());
		}
	}

	private void exchangeCodeAndStore(
			String code,
			String codeVerifier
	) throws IOException {
		String body =
				"grant_type=authorization_code"
				+ "&code=" + encode(code)
				+ "&redirect_uri=" + encode(REDIRECT_URI)
				+ "&client_id=" + encode(Antigravity.CLIENT_ID)
				+ "&client_secret=" + encode(Antigravity.CLIENT_SECRET)
				+ "&code_verifier=" + encode(codeVerifier);

		HttpRequest request =
				HttpRequest.newBuilder()
				           .uri(URI.create(Antigravity.GOOGLE_TOKEN_URL))
				           .header(
						           "Content-Type",
						           "application/x-www-form-urlencoded"
				           )
				           .POST(
						           HttpRequest.BodyPublishers.ofString(body)
				           )
				           .build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				throw new IOException(
						"Token exchange failed: " + response.statusCode() +
						" " + response.body()
				);
			}

			JsonNode json = MAPPER.readTree(response.body());
			String accessToken = json.get("access_token").asString();
			String refreshToken =
					json.has("refresh_token") ? json.get("refresh_token")
					                                .asString() : null;
			int expiresIn = json.get("expires_in").asInt();
			Instant expiresAt = Instant.now().plusSeconds(expiresIn);

			String email = fetchEmail(accessToken);
			String projectId = ProjectIdFetcher.fetch(accessToken);
			authManager.saveCredentials(
					accessToken,
					refreshToken,
					expiresAt,
					projectId,
					email
			);
			log.info(
					"[Antigravity] OAuth complete, email={}, projectId={}",
					email,
					projectId
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Token exchange interrupted", e);
		}
	}

	private static String generateCodeVerifier() {
		byte[] bytes = new byte[32];
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

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String fetchEmail(String accessToken) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(Antigravity.USERINFO_URL))
			                                 .header(
					                                 "Authorization",
					                                 "Bearer " + accessToken
			                                 )
			                                 .GET()
			                                 .build();
			HttpResponse<String> response = HTTP_CLIENT.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() == 200) {
				JsonNode json = MAPPER.readTree(response.body());
				JsonNode email = json.get("email");
				return email != null ? email.asString() : null;
			}
		} catch (Exception e) {
			log.warn("[Antigravity] Failed to fetch email: {}", e.getMessage());
		}
		return null;
	}
}
