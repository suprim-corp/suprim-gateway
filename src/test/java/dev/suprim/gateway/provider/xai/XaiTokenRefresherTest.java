package dev.suprim.gateway.provider.xai;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class XaiTokenRefresherTest {

	@Test
	void decodeIdTokenEmail_extractsEmail() {
		String idToken = buildIdToken("{\"email\":\"user@x.ai\",\"sub\":\"12345\"}");
		assertEquals("user@x.ai", XaiTokenRefresher.decodeIdTokenEmail(idToken));
	}

	@Test
	void decodeIdTokenEmail_fallsBackToPreferredUsername() {
		String idToken = buildIdToken("{\"preferred_username\":\"grokuser\",\"sub\":\"12345\"}");
		assertEquals("grokuser", XaiTokenRefresher.decodeIdTokenEmail(idToken));
	}

	@Test
	void decodeIdTokenEmail_fallsBackToSub() {
		String idToken = buildIdToken("{\"sub\":\"12345\"}");
		assertEquals("12345", XaiTokenRefresher.decodeIdTokenEmail(idToken));
	}

	@Test
	void decodeIdTokenEmail_returnsNullForInvalidToken() {
		assertNull(XaiTokenRefresher.decodeIdTokenEmail("not.a.valid"));
	}

	@Test
	void decodeIdTokenEmail_returnsNullForNull() {
		assertNull(XaiTokenRefresher.decodeIdTokenEmail(null));
	}

	@Test
	void decodeIdTokenEmail_returnsNullForMalformedParts() {
		assertNull(XaiTokenRefresher.decodeIdTokenEmail("onlyonepart"));
	}

	private static String buildIdToken(String payloadJson) {
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes());
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
		String signature = Base64.getUrlEncoder().withoutPadding().encodeToString("fake-sig".getBytes());
		return header + "." + payload + "." + signature;
	}
}
