package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.OAuthPkce;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Controller
@RequiredArgsConstructor
public class CodexOAuthController {

	private static final int DEFAULT_EXPIRES_IN = 3600;

	private final CodexAuthManager authManager;
	private final CodexLoopbackServer loopbackServer;

	/**
	 * Verifiers awaiting their authorization code, keyed by state.
	 * <p>
	 * The remote flow splits one login across two requests from two different
	 * machines, so the verifier cannot live on the client.
	 */
	private final ConcurrentHashMap<String, String> pendingVerifiers = new ConcurrentHashMap<>();

	@GetMapping("/auth/codex")
	String initiateOAuth(HttpServletRequest httpReq) {
		if (!OAuthPkce.isLoopback(httpReq)) {
			return "redirect:/auth/codex/remote?state=" + OAuthPkce.encode(newPendingState());
		}

		String codeVerifier = OAuthPkce.codeVerifier();
		String state = OAuthPkce.state();

		loopbackServer.start(
				codeVerifier,
				state,
				OAuthPkce.gatewayBase(httpReq),
				code -> completeLocalLogin(code, codeVerifier)
		);

		return "redirect:" + CodexAuthUrl.build(
				OAuthPkce.codeChallenge(codeVerifier),
				state,
				OAuthPkce.state()
		);
	}

	@GetMapping("/auth/codex/remote")
	@ResponseBody
	String remotePage(String state, HttpServletRequest httpReq) {
		return CodexRemoteSetupPages.instructionPage(
				OAuthPkce.gatewayBase(httpReq),
				state
		);
	}

	@GetMapping(value = "/auth/codex/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(String state, HttpServletRequest httpReq) {
		String codeVerifier = pendingVerifiers.get(state);
		if (codeVerifier == null) {
			return "echo 'Error: invalid or expired state'";
		}
		return CodexRemoteSetupPages.agentScript(
				OAuthPkce.gatewayBase(httpReq),
				state,
				codeVerifier,
				OAuthPkce.codeChallenge(codeVerifier)
		);
	}

	@PostMapping("/auth/codex/state")
	@ResponseBody
	Map<String, String> generateState() {
		return Map.of("state", newPendingState());
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
			String email = exchangeAndSave(code, codeVerifier);
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
		if (accessToken == null) {
			return Map.of("error", "missing access_token");
		}

		Object expiresIn = body.get("expires_in");
		String email = saveCredentials(
				accessToken,
				(String) body.get("refresh_token"),
				(String) body.get("id_token"),
				expiresIn instanceof Number n ? n.intValue() : DEFAULT_EXPIRES_IN
		);
		log.info("[Codex] OAuth complete (device flow), email={}", email);
		return Map.of("status", "ok", "email", email != null ? email : "");
	}

	private String newPendingState() {
		String state = OAuthPkce.state();
		pendingVerifiers.put(state, OAuthPkce.codeVerifier());
		return state;
	}

	private void completeLocalLogin(String code, String codeVerifier) {
		try {
			String email = exchangeAndSave(code, codeVerifier);
			log.info("[Codex] OAuth complete (local), email={}", email);
		} catch (Exception e) {
			log.error("[Codex] Token exchange failed: {}", e.getMessage());
			throw new IllegalStateException("Codex token exchange failed", e);
		}
	}

	/** Trades the authorization code for tokens and stores them. */
	private String exchangeAndSave(String code, String codeVerifier) throws Exception {
		CodexTokenResponse tokens = CodexTokenRefresher.exchangeCode(
				code,
				codeVerifier,
				Codex.REDIRECT_URI
		);
		return saveCredentials(
				tokens.accessToken(),
				tokens.refreshToken(),
				tokens.idToken(),
				tokens.expiresIn()
		);
	}

	/** @return the email read from the id_token, or null when absent. */
	private String saveCredentials(
			String accessToken,
			String refreshToken,
			String idToken,
			int expiresIn
	) {
		String email = CodexTokenRefresher.decodeIdTokenEmail(idToken);
		authManager.saveCredentials(
				accessToken,
				refreshToken,
				Instant.now().plusSeconds(expiresIn),
				email
		);
		return email;
	}
}
