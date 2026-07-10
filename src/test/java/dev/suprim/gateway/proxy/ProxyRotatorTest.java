package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProxyRotatorTest {

	@Test
	void current_returnsFirstProxy() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080"),
				ProxyEntry.parse("socks5|host2:1080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		assertEquals("host1", rotator.current().host());
	}

	@Test
	void advance_movesToNext() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080"),
				ProxyEntry.parse("socks5|host2:1080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		rotator.advance();

		assertEquals("host2", rotator.current().host());
	}

	@Test
	void advance_wrapsToZeroAfterLast() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080"),
				ProxyEntry.parse("socks5|host2:1080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		rotator.advance();
		rotator.advance();

		assertEquals("host1", rotator.current().host());
	}

	@Test
	void advance_singleProxy_staysAtZero() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		rotator.advance();

		assertEquals("host1", rotator.current().host());
	}

	@Test
	void emptyList_currentReturnsNull() {
		ProxyRotator rotator = ProxyRotator.of(Collections.emptyList());

		assertNull(rotator.current());
	}

	@Test
	void isExhausted_trueAfterFullCycle() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080"),
				ProxyEntry.parse("socks5|host2:1080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		assertFalse(rotator.isExhausted());
		rotator.advance();
		assertFalse(rotator.isExhausted());
		rotator.advance();
		assertTrue(rotator.isExhausted());
	}

	@Test
	void resetAttempts_clearsExhaustion() {
		List<ProxyEntry> entries = List.of(
				ProxyEntry.parse("http|host1:8080"),
				ProxyEntry.parse("socks5|host2:1080")
		);
		ProxyRotator rotator = ProxyRotator.of(entries);

		rotator.advance();
		rotator.advance();
		assertTrue(rotator.isExhausted());

		rotator.resetAttempts();
		assertFalse(rotator.isExhausted());
	}
}
