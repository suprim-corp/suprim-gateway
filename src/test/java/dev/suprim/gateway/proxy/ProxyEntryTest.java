package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyEntryTest {

	@Test
	void parse_httpWithAuth() {
		ProxyEntry entry = ProxyEntry.parse("http|user:pass@host.com:8080");

		assertEquals(ProxyEntry.Scheme.HTTP, entry.scheme());
		assertEquals("host.com", entry.host());
		assertEquals(8080, entry.port());
		assertEquals("user", entry.username());
		assertEquals("pass", entry.password());
	}

	@Test
	void parse_socks5WithAuth() {
		ProxyEntry entry = ProxyEntry.parse("socks5|admin:secret@proxy.sg:1080");

		assertEquals(ProxyEntry.Scheme.SOCKS5, entry.scheme());
		assertEquals("proxy.sg", entry.host());
		assertEquals(1080, entry.port());
		assertEquals("admin", entry.username());
		assertEquals("secret", entry.password());
	}

	@Test
	void parse_socks5NoAuth() {
		ProxyEntry entry = ProxyEntry.parse("socks5|proxy.sg:1080");

		assertEquals(ProxyEntry.Scheme.SOCKS5, entry.scheme());
		assertEquals("proxy.sg", entry.host());
		assertEquals(1080, entry.port());
		assertNull(entry.username());
		assertNull(entry.password());
	}

	@Test
	void parse_httpNoAuth() {
		ProxyEntry entry = ProxyEntry.parse("http|10.0.0.1:3128");

		assertEquals(ProxyEntry.Scheme.HTTP, entry.scheme());
		assertEquals("10.0.0.1", entry.host());
		assertEquals(3128, entry.port());
		assertNull(entry.username());
		assertNull(entry.password());
	}

	@Test
	void parse_invalidScheme_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> ProxyEntry.parse("https|host:8080"));
	}

	@Test
	void parse_missingPipe_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> ProxyEntry.parse("not-a-url"));
	}

	@Test
	void parse_missingPort_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> ProxyEntry.parse("http|host"));
	}

	@Test
	void maskedUrl_hidesPassword() {
		ProxyEntry entry = ProxyEntry.parse("socks5|user:secret123@host:1080");

		assertEquals("socks5|user:***@host:1080", entry.maskedUrl());
	}

	@Test
	void maskedUrl_noAuth() {
		ProxyEntry entry = ProxyEntry.parse("http|host:8080");

		assertEquals("http|host:8080", entry.maskedUrl());
	}

	@Test
	void parse_usernameOnly_noPassword() {
		ProxyEntry entry = ProxyEntry.parse("http|admin@host:8080");

		assertEquals(ProxyEntry.Scheme.HTTP, entry.scheme());
		assertEquals("host", entry.host());
		assertEquals(8080, entry.port());
		assertEquals("admin", entry.username());
		assertNull(entry.password());
	}

	@Test
	void maskedUrl_usernameOnly_noPassword() {
		ProxyEntry entry = ProxyEntry.parse("http|admin@host:8080");

		assertEquals("http|admin@host:8080", entry.maskedUrl());
	}
}
