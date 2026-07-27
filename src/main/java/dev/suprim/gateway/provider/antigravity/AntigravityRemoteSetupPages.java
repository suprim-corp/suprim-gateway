package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.provider.OAuthAgentScript;

/**
 * The remote-setup script for the Antigravity flow.
 * <p>
 * Google only accepts a {@code localhost} redirect, so a gateway reached over
 * the network cannot receive the callback itself. The script listens on the
 * loopback port, exchanges the code with Google directly, and posts the
 * resulting tokens back here.
 * <p>
 * Unlike the other providers the verifier is generated inside the script
 * rather than issued by the gateway: the token exchange also happens locally,
 * so the gateway never needs to know it.
 */
final class AntigravityRemoteSetupPages {

	private AntigravityRemoteSetupPages() {}

	static String agentScript(String gatewayBase) {
		return """
				#!/bin/bash
				GATEWAY='%s'
				REDIRECT_URI='%s'
				CLIENT_ID='%s'
				CLIENT_SECRET='%s'
				PORT=%d

				CODE_VERIFIER=$(openssl rand -hex 32)
				CODE_CHALLENGE=$(printf '%%s' "$CODE_VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
				STATE=$(openssl rand -hex 16)

				AUTH_URL="%s&code_challenge=${CODE_CHALLENGE}&state=${STATE}"

				%s
				%s
				%s
				echo 'Code received, exchanging for tokens...'

				TOKEN_RESP=$(curl -s -X POST '%s' \\
				  -H 'Content-Type: application/x-www-form-urlencoded' \\
				  -d "grant_type=authorization_code&code=$CODE&redirect_uri=$REDIRECT_URI&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code_verifier=$CODE_VERIFIER")

				if echo "$TOKEN_RESP" | grep -q '"access_token"'; then
				  echo 'Sending tokens to gateway...'
				  curl -sL -X POST "$GATEWAY/auth/antigravity/token-exchange" \\
				    -H 'Content-Type: application/json' \\
				    -d "$TOKEN_RESP"
				  echo ''
				  echo 'Done! Antigravity account connected.'
				else
				  echo "Error: $TOKEN_RESP"
				  exit 1
				fi
				""".formatted(
				gatewayBase,
				Antigravity.REDIRECT_URI,
				Antigravity.CLIENT_ID,
				Antigravity.CLIENT_SECRET,
				Antigravity.LOOPBACK_PORT,
				AntigravityAuthUrl.withoutChallenge(),
				OAuthAgentScript.OPEN_URL_FUNCTION,
				OAuthAgentScript.promptForUrl("Antigravity OAuth", "1;35"),
				OAuthAgentScript.CATCH_LOOPBACK_CODE,
				Antigravity.GOOGLE_TOKEN_URL
		);
	}
}
