package dev.suprim.gateway.provider;

/**
 * Shell fragments shared by the provider remote-setup scripts.
 * <p>
 * Every provider that cannot receive its OAuth callback on a remote gateway
 * hands the user a script to run locally, and each of those scripts needs the
 * same two things: a way to open a browser, and a way to catch the redirect on
 * a loopback port.
 */
public final class OAuthAgentScript {

	private OAuthAgentScript() {}

	/**
	 * Defines {@code open_url}, which picks a browser to open a URL with.
	 * <p>
	 * On macOS the default handler is often not the browser the user is signed
	 * into the provider with, so the installed browsers are listed and the user
	 * chooses. Elsewhere it falls through to {@code xdg-open}, and prints the
	 * URL when even that is unavailable.
	 */
	public static final String OPEN_URL_FUNCTION = """
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

	/**
	 * Blocks on {@code $PORT} with {@code nc} until the provider redirects the
	 * browser there, then leaves the authorization code in {@code $CODE} and
	 * bounces the browser to the gateway's success page.
	 * <p>
	 * Two {@code nc} invocations are attempted because the BSD and GNU builds
	 * disagree on whether the listen port takes {@code -p}.
	 */
	public static final String CATCH_LOOPBACK_CODE = """
			TMPFILE=$(mktemp)
			RESPONSE="HTTP/1.1 302 Found\\r\\nLocation: $GATEWAY/oauth-success.html\\r\\nConnection: close\\r\\n\\r\\n"
			{ printf '%b' "$RESPONSE"; } | nc -l $PORT > "$TMPFILE" 2>/dev/null || { printf '%b' "$RESPONSE"; } | nc -l -p $PORT > "$TMPFILE" 2>/dev/null
			REQUEST=$(cat "$TMPFILE")
			rm -f "$TMPFILE"
			CODE=$(echo "$REQUEST" | sed -n 's/.*code=\\([^& ]*\\).*/\\1/p' | head -1)

			if [ -z "$CODE" ]; then echo 'Error: no code received'; exit 1; fi""";

	/**
	 * Banner shown before the consent URL.
	 *
	 * @param ansiColor SGR parameters for the title, e.g. {@code 1;34}
	 */
	public static String promptForUrl(String title, String ansiColor) {
		return """
				echo ''
				printf '\\033[%sm  %s\\033[0m\\n'
				echo ''
				printf '  Open this URL in your browser to authorize:\\033[0m\\n'
				echo ''
				printf '  \\033[4;36m%%s\\033[0m\\n' "$AUTH_URL"
				echo ''
				printf '  \\033[2mWaiting for callback on localhost:%%s...\\033[0m\\n' $PORT
				echo ''
				open_url "$AUTH_URL\"""".formatted(ansiColor, title);
	}
}
