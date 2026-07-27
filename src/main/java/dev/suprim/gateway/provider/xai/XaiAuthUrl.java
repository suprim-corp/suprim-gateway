package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.instants.Xai;
import dev.suprim.gateway.provider.OAuthPkce;

/**
 * Builds the xAI authorize URL used by the local loopback flow.
 * <p>
 * {@code plan} and {@code referrer} are what the Grok CLI sends; they select
 * the consent screen xAI shows for CLI clients.
 */
final class XaiAuthUrl {

	private XaiAuthUrl() {}

	static String build(String codeChallenge, String state, String nonce) {
		return Xai.AUTH_URL
		       + "?response_type=code"
		       + "&client_id=" + OAuthPkce.encode(Xai.CLIENT_ID)
		       + "&redirect_uri=" + OAuthPkce.encode(Xai.REDIRECT_URI)
		       + "&scope=" + OAuthPkce.encode(Xai.SCOPE)
		       + "&code_challenge=" + OAuthPkce.encode(codeChallenge)
		       + "&code_challenge_method=S256"
		       + "&state=" + OAuthPkce.encode(state)
		       + "&nonce=" + OAuthPkce.encode(nonce)
		       + "&plan=generic"
		       + "&referrer=cli-proxy-api";
	}
}
