package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;

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
				echo ''
				printf '\\033[1;34m  OpenAI Codex Authorization\\033[0m\\n'
				echo ''
				printf '  Open this URL in your browser to authorize:\\033[0m\\n'
				echo ''
				printf '  \\033[4;36m%%s\\033[0m\\n' "$AUTH_URL"
				echo ''
				printf '  \\033[2mWaiting for callback on localhost:%%s...\\033[0m\\n' $PORT
				echo ''
				open_url "$AUTH_URL"
				TMPFILE=$(mktemp)
				RESPONSE="HTTP/1.1 302 Found\\r\\nLocation: $GATEWAY/oauth-success.html\\r\\nConnection: close\\r\\n\\r\\n"
				{ printf '%%b' "$RESPONSE"; } | nc -l $PORT > "$TMPFILE" 2>/dev/null || { printf '%%b' "$RESPONSE"; } | nc -l -p $PORT > "$TMPFILE" 2>/dev/null
				REQUEST=$(cat "$TMPFILE")
				rm -f "$TMPFILE"
				CODE=$(echo "$REQUEST" | sed -n 's/.*code=\\([^& ]*\\).*/\\1/p' | head -1)

				if [ -z "$CODE" ]; then echo 'Error: no code received'; exit 1; fi
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
				OPEN_URL_FUNCTION
		);
	}

	/**
	 * Picks a browser to open the consent screen with. On macOS the default
	 * handler is often not what the user signed into ChatGPT with, so the
	 * installed browsers are listed and the user chooses.
	 */
	private static final String OPEN_URL_FUNCTION = """
			open_url() {
			  if [ "$(uname)" = 'Darwin' ]; then
			    BROWSERS=()
			    for app in /Applications/*.app; do
			      if plutil -extract CFBundleURLTypes json -o - "$app/Contents/Info.plist" 2>/dev/null | grep -q '"https"'; then
			        if plutil -extract CFBundleDocumentTypes json -o - "$app/Contents/Info.plist" 2>/dev/null | grep -qi 'html'; then
			          BROWSERS+=("$(basename "$app" .app)")
			        fi
			      fi
			    done
			    if [ ${#BROWSERS[@]} -eq 0 ]; then
			      open "$1"
			    else
			      echo 'Select browser:'
			      for i in "${!BROWSERS[@]}"; do echo "  $((i+1))) ${BROWSERS[$i]}"; done
			      echo "  $((${#BROWSERS[@]}+1))) Default"
			      printf 'Choice [%d]: ' $((${#BROWSERS[@]}+1))
			      read -r PICK </dev/tty
			      PICK=${PICK:-$((${#BROWSERS[@]}+1))}
			      if [ "$PICK" -gt ${#BROWSERS[@]} ] 2>/dev/null; then
			        open "$1"
			      else
			        open -a "${BROWSERS[$((PICK-1))]}" "$1"
			      fi
			    fi
			  else
			    xdg-open "$1" 2>/dev/null || echo "Open this URL: $1"
			  fi
			}""";
}
