package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.instants.Xai;
import dev.suprim.gateway.provider.OAuthAgentScript;

/**
 * The two artifacts the xAI remote-setup flow serves.
 * <p>
 * Unlike the other providers this script runs the OAuth <em>device</em> flow:
 * there is no loopback listener, the script polls the token endpoint until the
 * user finishes authorizing in a browser. That is why it needs no callback
 * port and ignores the PKCE verifier the gateway issued.
 */
final class XaiRemoteSetupPages {

	private XaiRemoteSetupPages() {}

	static String instructionPage(String gatewayBase, String state) {
		return """
				<!DOCTYPE html>
				<html><head><title>xAI Connect</title>
				<style>body{font-family:system-ui;background:#0a0a0a;color:#e4e4e7;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
				.card{text-align:center;padding:2rem;border:1px solid #27272a;border-radius:8px;background:#18181b;max-width:600px}
				h1{font-size:1.25rem;margin:0 0 1rem}p{color:#a1a1aa;font-size:0.875rem;margin:0 0 1rem}
				pre{background:#09090b;border:1px solid #27272a;border-radius:4px;padding:1rem;text-align:left;font-size:0.75rem;overflow-x:auto;color:#a1a1aa}
				code{color:#c084fc}</style></head>
				<body><div class="card">
				<h1>xAI OAuth (Remote Setup)</h1>
				<p>xAI requires localhost callback. Run this on your local machine:</p>
				<pre><code>curl -sL "%s/auth/xai/agent?state=%s" | bash</code></pre>
				<p>After login, token will be sent back to this gateway automatically.</p>
				</div></body></html>
				""".formatted(gatewayBase, state);
	}

	/**
	 * Requests a device code, shows the user the verification URL and code,
	 * then polls until authorization completes and posts the tokens back.
	 * <p>
	 * {@code authorization_pending} and {@code slow_down} are the two errors
	 * the device flow expects while waiting; anything else aborts.
	 */
	static String agentScript(String gatewayBase, String state) {
		return """
				#!/bin/bash
				GATEWAY='%s'
				STATE='%s'
				CLIENT_ID='%s'
				SCOPE='%s'
				echo 'Starting xAI device code flow...'
				DEVICE_RESP=$(curl -s -X POST '%s' \\
				  -H 'Content-Type: application/x-www-form-urlencoded' \\
				  -d "client_id=$CLIENT_ID&scope=$SCOPE")
				DEVICE_CODE=$(echo "$DEVICE_RESP" | grep -o '"device_code":"[^"]*"' | cut -d'"' -f4)
				USER_CODE=$(echo "$DEVICE_RESP" | grep -o '"user_code":"[^"]*"' | cut -d'"' -f4)
				VERIFY_URI=$(echo "$DEVICE_RESP" | grep -o '"verification_uri":"[^"]*"' | cut -d'"' -f4)
				INTERVAL=$(echo "$DEVICE_RESP" | grep -o '"interval":[0-9]*' | cut -d: -f2)
				[ -z "$INTERVAL" ] && INTERVAL=5
				if [ -z "$DEVICE_CODE" ]; then echo "Error: $DEVICE_RESP"; exit 1; fi
				echo ''
				printf '\\033[1;35m  xAI Device Authorization\\033[0m\\n'
				echo ''
				printf '  Open this URL and enter the code below:\\n'
				echo ''
				printf '  \\033[4;36m%%s\\033[0m\\n' "$VERIFY_URI"
				echo ''
				printf '  Code: \\033[1;33m%%s\\033[0m\\n' "$USER_CODE"
				echo ''
				%s
				open_url "$VERIFY_URI"
				echo ''
				printf '  \\033[2mWaiting for authorization...\\033[0m\\n'
				echo ''
				while true; do
				  sleep $INTERVAL
				  TOKEN_RESP=$(curl -s -X POST '%s' \\
				    -H 'Content-Type: application/x-www-form-urlencoded' \\
				    -d "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=$DEVICE_CODE&client_id=$CLIENT_ID")
				  if echo "$TOKEN_RESP" | grep -q '"access_token"'; then
				    echo 'Authorized! Sending tokens to gateway...'
				    curl -sL -X POST "$GATEWAY/auth/xai/device-exchange" \\
				      -H 'Content-Type: application/json' \\
				      -d "$TOKEN_RESP"
				    echo ''
				    echo 'Done! xAI account connected.'
				    exit 0
				  fi
				  if echo "$TOKEN_RESP" | grep -q '"error"'; then
				    ERR=$(echo "$TOKEN_RESP" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
				    if [ "$ERR" != "authorization_pending" ] && [ "$ERR" != "slow_down" ]; then
				      echo "Error: $ERR"; exit 1
				    fi
				    [ "$ERR" = "slow_down" ] && INTERVAL=$((INTERVAL+5))
				  fi
				  printf '.'
				done
				""".formatted(
				gatewayBase,
				state,
				Xai.CLIENT_ID,
				Xai.SCOPE,
				Xai.DEVICE_CODE_URL,
				OAuthAgentScript.OPEN_URL_FUNCTION,
				Xai.TOKEN_URL
		);
	}
}
