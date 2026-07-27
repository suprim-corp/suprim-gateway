package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.provider.OAuthPkce;

/**
 * Builds the Google authorize URL for the Antigravity flow.
 * <p>
 * {@code access_type=offline} is what yields a refresh token, so the gateway
 * can keep the account connected past the first hour.
 */
final class AntigravityAuthUrl {

	private AntigravityAuthUrl() {}

	/**
	 * URL for the local loopback flow.
	 * <p>
	 * The scope is encoded with {@code %20} rather than {@code +}: Google's
	 * consent screen renders a literal plus sign in the scope list otherwise.
	 */
	static String build(String codeChallenge) {
		return Antigravity.GOOGLE_AUTH_URL
		       + "?client_id=" + OAuthPkce.encode(Antigravity.CLIENT_ID)
		       + "&redirect_uri=" + OAuthPkce.encode(Antigravity.REDIRECT_URI)
		       + "&response_type=code"
		       + "&scope=" + OAuthPkce.encode(Antigravity.OAUTH_SCOPE)
		                              .replace("+", "%20")
		       + "&code_challenge=" + OAuthPkce.encode(codeChallenge)
		       + "&code_challenge_method=S256"
		       + "&access_type=offline";
	}

	/**
	 * URL for the remote setup script, which appends its own
	 * {@code code_challenge} and {@code state} after generating them locally.
	 * <p>
	 * {@code prompt=consent} is added because the script's account may already
	 * have granted these scopes, and Google then skips issuing a refresh token.
	 */
	static String withoutChallenge() {
		return Antigravity.GOOGLE_AUTH_URL
		       + "?client_id=" + OAuthPkce.encode(Antigravity.CLIENT_ID)
		       + "&redirect_uri=" + OAuthPkce.encode(Antigravity.REDIRECT_URI)
		       + "&response_type=code"
		       + "&scope=" + OAuthPkce.encode(Antigravity.OAUTH_SCOPE)
		       + "&code_challenge_method=S256"
		       + "&access_type=offline"
		       + "&prompt=consent";
	}
}
