package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

class ProxyClientFactoryTest {

	@Test
	void build_nullEntry_returnsDirectClient() {
		HttpClient client = ProxyClientFactory.build(null);

		assertNotNull(client);
	}

	@Test
	void build_httpProxy_returnsClientWithProxy() {
		ProxyEntry entry = ProxyEntry.parse("http|proxy.host:8080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
	}

	@Test
	void build_socks5Proxy_returnsClientWithProxy() {
		ProxyEntry entry = ProxyEntry.parse("socks5|proxy.host:1080");

		HttpClient client = ProxyClientFactory.build(entry);

		assertNotNull(client);
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
}
