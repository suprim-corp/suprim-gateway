package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.provider.OAuthPkce;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AntigravityOAuthController {

	/**
	 * Verifier entropy in bytes. Shorter than the other providers', kept as-is
	 * so adopting the shared helper does not change what this flow sends.
	 */
	private static final int VERIFIER_BYTES = 32;
	private static final int DEFAULT_EXPIRES_IN = 3600;

	private final AntigravityAuthManager authManager;
	private final AntigravityLoopbackServer loopbackServer;

	@GetMapping("/auth/antigravity")
	String initiateOAuth(HttpServletRequest httpReq) {
		String codeVerifier = OAuthPkce.codeVerifier(VERIFIER_BYTES);

		loopbackServer.start(
				codeVerifier,
				OAuthPkce.gatewayBase(httpReq),
				code -> completeLocalLogin(code, codeVerifier)
		);

		return "redirect:" + AntigravityAuthUrl.build(
				OAuthPkce.codeChallenge(codeVerifier)
		);
	}

	@GetMapping(value = "/auth/antigravity/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(HttpServletRequest httpReq) {
		return AntigravityRemoteSetupPages.agentScript(
				OAuthPkce.gatewayBase(httpReq)
		);
	}

	/**
	 * Receives tokens the remote setup script already obtained from Google.
	 */
	@PostMapping("/auth/antigravity/token-exchange")
	@ResponseBody
	Map<String, String> tokenExchange(@RequestBody Map<String, Object> body) {
		String accessToken = (String) body.get("access_token");
		if (accessToken == null) {
			return Map.of("error", "missing access_token");
		}

		Object expiresIn = body.get("expires_in");
		try {
			String email = saveCredentials(
					accessToken,
					(String) body.get("refresh_token"),
					expiresIn instanceof Number n ? n.intValue() : DEFAULT_EXPIRES_IN,
					"remote"
			);
			return Map.of("status", "ok", "email", email != null ? email : "");
		} catch (Exception e) {
			log.error(
					"[Antigravity] Remote token exchange failed: {}",
					e.getMessage()
			);
			return Map.of("error", e.getMessage());
		}
	}

	private void completeLocalLogin(String code, String codeVerifier) {
		try {
			AntigravityGoogleTokens.Tokens tokens =
					AntigravityGoogleTokens.exchangeCode(code, codeVerifier);
			saveCredentials(
					tokens.accessToken(),
					tokens.refreshToken(),
					tokens.expiresIn(),
					"loopback"
			);
		} catch (Exception e) {
			log.error("[Antigravity] Token exchange failed: {}", e.getMessage());
			throw new IllegalStateException("Antigravity token exchange failed", e);
		}
	}

	/**
	 * Resolves the account's email and Cloud project, then stores everything.
	 *
	 * @return the email, or null when Google did not report one
	 */
	private String saveCredentials(
			String accessToken,
			String refreshToken,
			int expiresIn,
			String flow
	) throws IOException {
		String email = AntigravityGoogleTokens.fetchEmail(accessToken);
		String projectId = ProjectIdFetcher.fetch(accessToken);
		authManager.saveCredentials(
				accessToken,
				refreshToken,
				Instant.now().plusSeconds(expiresIn),
				projectId,
				email
		);
		log.info(
				"[Antigravity] OAuth complete ({}), email={}, projectId={}",
				flow,
				email,
				projectId
		);
		return email;
	}
}
