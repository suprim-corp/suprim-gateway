package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.OAuthPkce;

/**
 * Builds the OpenAI authorize URL the Codex CLI itself uses.
 * <p>
 * The extra parameters beyond plain OAuth ({@code id_token_add_organizations},
 * {@code codex_cli_simplified_flow}, {@code originator}) are what the real
 * client sends; dropping them changes which consent screen the user sees and
 * whether the id_token carries organization claims.
 */
final class CodexAuthUrl {

	private CodexAuthUrl() {}

	/**
	 * Authorize URL without {@code code_challenge} and {@code state}.
	 * The remote flow appends those in a shell script, so they are kept
	 * separate rather than interpolated here.
	 */
	static String withoutChallenge() {
		return Codex.AUTH_URL
		       + "?response_type=code"
		       + "&client_id=" + OAuthPkce.encode(Codex.CLIENT_ID)
		       + "&redirect_uri=" + OAuthPkce.encode(Codex.REDIRECT_URI)
		       + "&scope=" + OAuthPkce.encode(Codex.SCOPE)
		       + "&code_challenge_method=S256"
		       + "&id_token_add_organizations=true"
		       + "&codex_cli_simplified_flow=true"
		       + "&originator=" + OAuthPkce.encode(Codex.ORIGINATOR);
	}

	static String build(String codeChallenge, String state, String nonce) {
		return withoutChallenge()
		       + "&code_challenge=" + OAuthPkce.encode(codeChallenge)
		       + "&state=" + OAuthPkce.encode(state)
		       + "&nonce=" + OAuthPkce.encode(nonce);
	}
}
