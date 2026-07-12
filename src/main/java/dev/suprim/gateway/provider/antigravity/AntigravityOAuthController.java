package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
				+ "&scope=" + encode(Antigravity.OAUTH_SCOPE)
				+ "&code_challenge=" + encode(codeChallenge)
				+ "&code_challenge_method=S256"
				+ "&access_type=offline"
				+ "&prompt=consent";

		return "redirect:" + url;
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
