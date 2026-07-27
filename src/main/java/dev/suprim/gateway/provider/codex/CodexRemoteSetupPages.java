package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.OAuthAgentScript;

/**
 * The two artifacts the remote-setup flow serves.
 * <p>
 * OpenAI only accepts a {@code localhost} redirect, so a gateway reached over
 * the network cannot receive the callback itself. Instead it hands the user a
 * script to run on their own machine, which listens on the loopback port and
 * posts the resulting code back here.
 */
final class CodexRemoteSetupPages {

	private CodexRemoteSetupPages() {}

	static String instructionPage(String gatewayBase, String state) {
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

	/**
	 * Shell script that opens the consent screen, catches the redirect on the
	 * loopback port with {@code nc}, and forwards the authorization code to
	 * this gateway's exchange endpoint.
	 * <p>
	 * The verifier is embedded so the gateway does not have to keep it in
	 * memory across two separate HTTP requests from different machines.
	 */
	static String agentScript(
			String gatewayBase,
			String state,
			String codeVerifier,
			String codeChallenge
	) {
		return """
				#!/bin/bash
				GATEWAY='%s'
				STATE='%s'
				CODE_VERIFIER='%s'
				CODE_CHALLENGE='%s'
				PORT=%d

				AUTH_URL="%s&code_challenge=${CODE_CHALLENGE}&state=${STATE}"

				%s
				%s
				%s
				echo 'Code received, exchanging tokens...'

				curl -sL -X POST "$GATEWAY/auth/codex/exchange" \\
				  -H 'Content-Type: application/json' \\
				  -d '{"code":"'"$CODE"'","state":"'"$STATE"'"}'
				echo ''
				echo 'Done! Codex account connected.'
				""".formatted(
				gatewayBase,
				state,
				codeVerifier,
				codeChallenge,
				Codex.LOOPBACK_PORT,
				CodexAuthUrl.withoutChallenge(),
				OAuthAgentScript.OPEN_URL_FUNCTION,
				OAuthAgentScript.promptForUrl(
						"OpenAI Codex Authorization",
						"1;34"
				),
				OAuthAgentScript.CATCH_LOOPBACK_CODE
		);
	}

}
