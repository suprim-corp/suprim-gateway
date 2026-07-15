package dev.suprim.gateway.instants;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class InstantsTest {

	@Test
	void antigravity_clientIdIsDecodedRot13() {
		// ROT13 of the encoded value should produce a valid Google client ID format
		assertNotNull(Antigravity.CLIENT_ID);
		assertTrue(Antigravity.CLIENT_ID.contains(".apps.googleusercontent.com"));
	}

	@Test
	void antigravity_clientSecretIsDecodedRot13() {
		assertNotNull(Antigravity.CLIENT_SECRET);
		assertTrue(Antigravity.CLIENT_SECRET.startsWith("GOCSPX-"));
	}

	@Test
	void antigravity_constantsNotNull() {
		assertNotNull(Antigravity.CLOUDCODE_BASE);
		assertNotNull(Antigravity.CLOUDCODE_MODELS);
		assertNotNull(Antigravity.GOOGLE_AUTH_URL);
		assertNotNull(Antigravity.GOOGLE_TOKEN_URL);
		assertNotNull(Antigravity.PROVIDER);
	}

	@Test
	void xai_constantsNotNull() {
		assertNotNull(Xai.PROVIDER);
		assertNotNull(Xai.API_BASE);
		assertNotNull(Xai.MODEL_NAMES);
		assertFalse(Xai.MODEL_NAMES.isEmpty());
	}

	@Test
	void kiro_constantsNotNull() {
		assertNotNull(Kiro.PROVIDER);
		assertNotNull(Kiro.API_HOST_TEMPLATE);
		assertNotNull(Kiro.AMZ_TARGET);
	}

	@Test
	void codex_constantsNotNull() {
		assertNotNull(Codex.PROVIDER);
		assertNotNull(Codex.API_BASE);
		assertNotNull(Codex.MODEL_NAMES);
		assertFalse(Codex.MODEL_NAMES.isEmpty());
	}

	@Test
	void antigravity_privateConstructor() throws Exception {
		Constructor<Antigravity> ctor = Antigravity.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertNotNull(ctor.newInstance());
	}

	@Test
	void xai_privateConstructor() throws Exception {
		Constructor<Xai> ctor = Xai.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertNotNull(ctor.newInstance());
	}

	@Test
	void kiro_privateConstructor() throws Exception {
		Constructor<Kiro> ctor = Kiro.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertNotNull(ctor.newInstance());
	}

	@Test
	void codex_privateConstructor() throws Exception {
		Constructor<Codex> ctor = Codex.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertNotNull(ctor.newInstance());
	}

	@Test
	void rot13_lowercase() throws Exception {
		assertEquals("nop", invokeRot("abc"));
	}

	@Test
	void rot13_uppercase() throws Exception {
		assertEquals("NOP", invokeRot("ABC"));
	}

	@Test
	void rot13_nonAlpha() throws Exception {
		assertEquals("123-.", invokeRot("123-."));
	}

	@Test
	void rot13_mixed() throws Exception {
		assertEquals("Uryyb-123", invokeRot("Hello-123"));
	}

	@Test
	void rot13_boundaryChars() throws Exception {
		// chars just outside a-z and A-Z ranges: '@' (before A), '[' (after Z), '`' (before a), '{' (after z)
		assertEquals("@[`{", invokeRot("@[`{"));
	}

	@Test
	void rot13_emptyString() throws Exception {
		assertEquals("", invokeRot(""));
	}

	private static String invokeRot(String input) throws Exception {
		Method method = Antigravity.class.getDeclaredMethod("rot", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, input);
	}
}
