package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexHeadersTest {

	@Test
	void accountId_readsChatgptAccountIdFromAuthClaim() {
		String token = jwt("""
				{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-123"}}
				""");

		assertEquals(Optional.of("acct-123"), CodexHeaders.accountId(token));
	}

	@Test
	void accountId_emptyWhenAuthClaimMissing() {
		assertEquals(
				Optional.empty(),
				CodexHeaders.accountId(jwt("{\"email\":\"a@b.c\"}"))
		);
	}

	@Test
	void accountId_emptyWhenAccountIdBlank() {
		String token = jwt("""
				{"https://api.openai.com/auth":{"chatgpt_account_id":""}}
				""");

		assertEquals(Optional.empty(), CodexHeaders.accountId(token));
	}

	@Test
	void accountId_emptyForApiKeyStyleToken() {
		assertEquals(Optional.empty(), CodexHeaders.accountId("sk-not-a-jwt"));
		assertEquals(Optional.empty(), CodexHeaders.accountId(null));
	}

	@Test
	void apply_sendsAccountIdHeaderWhenTokenDeclaresOne() {
		String token = jwt("""
				{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9"}}
				""");

		HttpHeaders headers = headersFor(token);

		assertEquals(
				Optional.of("acct-9"),
				headers.firstValue("ChatGPT-Account-Id")
		);
		assertEquals(
				Optional.of("Bearer " + token),
				headers.firstValue("Authorization")
		);
		assertEquals(
				Optional.of(Codex.ORIGINATOR),
				headers.firstValue("originator")
		);
	}

	@Test
	void apply_omitsAccountIdHeaderWhenTokenHasNone() {
		HttpHeaders headers = headersFor("sk-plain-key");

		assertFalse(headers.firstValue("ChatGPT-Account-Id").isPresent());
		assertTrue(headers.firstValue("Authorization").isPresent());
	}

	@Test
	void apply_userAgentCarriesOriginatorVersionPlatformAndTerminal() {
		String userAgent = headersFor("sk-plain-key")
				.firstValue("User-Agent")
				.orElseThrow();

		assertTrue(
				userAgent.matches(
						Codex.ORIGINATOR + "/" + Codex.CLIENT_VERSION.replace(
								".",
								"\\."
						) + " \\(\\S+ \\S+; \\S+\\) unknown"
				),
				"unexpected User-Agent: " + userAgent
		);
	}

	private static HttpHeaders headersFor(String accessToken) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
		                                        .uri(URI.create("https://example.test"))
		                                        .GET();
		CodexHeaders.apply(builder, accessToken);
		return builder.build().headers();
	}

	private static String jwt(String payloadJson) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
		       + "."
		       + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
		       + ".sig";
	}
}
