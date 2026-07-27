package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.instants.Xai;
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
public class XaiOAuthController {

	/** xAI device-flow tokens are long-lived; used when the response omits it. */
	private static final int DEFAULT_EXPIRES_IN = 604800;

	private final XaiAuthManager authManager;
	private final XaiLoopbackServer loopbackServer;

	/**
	 * Verifiers awaiting their authorization code, keyed by state.
	 * <p>
	 * The remote flow splits one login across two requests from two different
	 * machines, so the verifier cannot live on the client.
	 */
	private final ConcurrentHashMap<String, String> pendingVerifiers = new ConcurrentHashMap<>();

	@GetMapping("/auth/xai")
	String initiateOAuth(HttpServletRequest httpReq) {
		if (!OAuthPkce.isLoopback(httpReq)) {
			return "redirect:/auth/xai/remote?state=" + OAuthPkce.encode(newPendingState());
		}

		String codeVerifier = OAuthPkce.codeVerifier();
		String state = OAuthPkce.state();

		loopbackServer.start(
				codeVerifier,
				state,
				OAuthPkce.gatewayBase(httpReq),
				code -> completeLocalLogin(code, codeVerifier)
		);

		return "redirect:" + XaiAuthUrl.build(
				OAuthPkce.codeChallenge(codeVerifier),
				state,
				OAuthPkce.state()
		);
	}

	@GetMapping("/auth/xai/remote")
	@ResponseBody
	String remotePage(String state, HttpServletRequest httpReq) {
		return XaiRemoteSetupPages.instructionPage(
				OAuthPkce.gatewayBase(httpReq),
				state
		);
	}

	@GetMapping(value = "/auth/xai/agent", produces = "text/plain")
	@ResponseBody
	String agentScript(String state, HttpServletRequest httpReq) {
		if (pendingVerifiers.get(state) == null) {
			return "echo 'Error: invalid or expired state'";
		}
		return XaiRemoteSetupPages.agentScript(
				OAuthPkce.gatewayBase(httpReq),
				state
		);
	}

	@PostMapping("/auth/xai/state")
	@ResponseBody
	Map<String, String> generateState() {
		return Map.of("state", newPendingState());
	}

	@PostMapping("/auth/xai/device-exchange")
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
		log.info("[xAI] OAuth complete (device flow), email={}", email);
		return Map.of("status", "ok", "email", email != null ? email : "");
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
			String email = exchangeAndSave(code, codeVerifier);
			log.info("[xAI] OAuth complete (remote), email={}", email);
			return Map.of("status", "ok", "email", email != null ? email : "");
		} catch (Exception e) {
			log.error("[xAI] Remote token exchange failed: {}", e.getMessage());
			return Map.of("error", e.getMessage());
		}
	}

	private String newPendingState() {
		String state = OAuthPkce.state();
		pendingVerifiers.put(state, OAuthPkce.codeVerifier());
		return state;
	}

	private void completeLocalLogin(String code, String codeVerifier) {
		try {
			String email = exchangeAndSave(code, codeVerifier);
			log.info("[xAI] OAuth complete (local), email={}", email);
		} catch (Exception e) {
			log.error("[xAI] Token exchange failed: {}", e.getMessage());
			throw new IllegalStateException("xAI token exchange failed", e);
		}
	}

	/** Trades the authorization code for tokens and stores them. */
	private String exchangeAndSave(String code, String codeVerifier) throws Exception {
		XaiTokenResponse tokens = XaiTokenRefresher.exchangeCode(
				code,
				codeVerifier,
				Xai.REDIRECT_URI
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
		String email = XaiTokenRefresher.decodeIdTokenEmail(idToken);
		authManager.saveCredentials(
				accessToken,
				refreshToken,
				Instant.now().plusSeconds(expiresIn),
				email
		);
		return email;
	}
}
