package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProxyClientFactoryTest {

	@Test
	void build_nullEntry_returnsDirectClient() {
		HttpClient client = ProxyClientFactory.build(null);

		assertNotNull(client);
		assertFalse(client.proxy().isPresent());
	}

	@Test
	void build_httpProxy_returnsClientWithProxy() {
		ProxyEntry entry = ProxyEntry.parse("http|proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
		assertTrue(client.proxy().isPresent());
		ProxySelector selector = client.proxy().get();
		List<Proxy> proxies = selector.select(URI.create("https://example.com"));
		assertEquals(1, proxies.size());
		assertEquals(Proxy.Type.HTTP, proxies.getFirst().type());
	}

	@Test
	void build_socks5Proxy_returnsClientWithProxy() {
		ProxyEntry entry = ProxyEntry.parse("socks5|proxy.host:1080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
		assertTrue(client.proxy().isPresent());
		ProxySelector selector = client.proxy().get();
		List<Proxy> proxies = selector.select(URI.create("https://example.com"));
		assertEquals(1, proxies.size());
		assertEquals(Proxy.Type.SOCKS, proxies.getFirst().type());
	}

	@Test
	void build_httpProxyWithAuth_returnsClientWithAuthenticator() {
		ProxyEntry entry = ProxyEntry.parse("http|user:pass@proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
		assertTrue(client.authenticator().isPresent());
	}

	@Test
	void build_proxyWithoutAuth_noAuthenticator() {
		ProxyEntry entry = ProxyEntry.parse("http|proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
		assertFalse(client.authenticator().isPresent());
	}

	@Test
	void build_proxyUsernameOnly_noAuthenticator() {
		ProxyEntry entry = ProxyEntry.parse("http|admin@proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
		assertFalse(client.authenticator().isPresent());
	}

	@Test
	void build_proxySelector_connectFailed_doesNotThrow() {
		ProxyEntry entry = ProxyEntry.parse("http|proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);
		ProxySelector selector = client.proxy().get();

		assertDoesNotThrow(() -> selector.connectFailed(
				URI.create("https://example.com"),
				InetSocketAddress.createUnresolved("proxy.host", 8080),
				new java.io.IOException("test")
		));
	}

	@Test
	void createAuthenticator_returnsCorrectCredentials() {
		ProxyEntry entry = ProxyEntry.parse("http|myuser:mypass@proxy.host:8080");

		Authenticator auth = ProxyClientFactory.createAuthenticator(entry);
		Authenticator.setDefault(auth);

		try {
			java.net.PasswordAuthentication pa = Authenticator.requestPasswordAuthentication(
					"proxy.host", null, 8080, "http", "", "basic"
			);
			assertNotNull(pa);
			assertEquals("myuser", pa.getUserName());
			assertEquals("mypass", new String(pa.getPassword()));
		} finally {
			Authenticator.setDefault(null);
		}
	}
}
