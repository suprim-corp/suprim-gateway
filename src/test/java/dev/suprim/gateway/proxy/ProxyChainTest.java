package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProxyChainTest {

	@TempDir
	Path tempDir;

	@Test
	void validFile_currentClientReturnsProxiedClient() throws IOException {
		Path file = writeProxiesFile("http|host1:8080", "socks5|host2:1080");

		ProxyChain chain = ProxyChain.of(file);

		HttpClient client = chain.currentClient();
		assertNotNull(client);
		assertTrue(client.proxy().isPresent());
	}

	@Test
	void emptyFile_currentClientReturnsDirect() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(
				file, """
						{ "proxies": [] }
						"""
		);

		ProxyChain chain = ProxyChain.of(file);

		HttpClient client = chain.currentClient();
		assertNotNull(client);
		assertFalse(client.proxy().isPresent());
	}

	@Test
	void missingFile_currentClientReturnsDirect() {
		Path file = tempDir.resolve("nonexistent.json");

		ProxyChain chain = ProxyChain.of(file);

		HttpClient client = chain.currentClient();
		assertNotNull(client);
		assertFalse(client.proxy().isPresent());
	}

	@Test
	void onFailure_advancesToNextProxy() throws IOException {
		Path file = writeProxiesFile("http|host1:8080", "socks5|host2:1080");

		ProxyChain chain = ProxyChain.of(file);
		HttpClient first = chain.currentClient();

		chain.onFailure();
		HttpClient second = chain.currentClient();

		assertNotSame(first, second);
	}

	@Test
	void onFailure_allExhausted_returnsNull() throws IOException {
		Path file = writeProxiesFile("http|host1:8080", "socks5|host2:1080");

		ProxyChain chain = ProxyChain.of(file);
		chain.onFailure();
		chain.onFailure();

		assertNull(chain.currentClient());
	}

	@Test
	void resetAttempts_allowsRetryAfterExhaustion() throws IOException {
		Path file = writeProxiesFile("http|host1:8080", "socks5|host2:1080");

		ProxyChain chain = ProxyChain.of(file);
		chain.onFailure();
		chain.onFailure();
		assertNull(chain.currentClient());

		chain.resetAttempts();
		assertNotNull(chain.currentClient());
	}

	@Test
	void hasProxies_trueWhenFileHasEntries() throws IOException {
		Path file = writeProxiesFile("http|host1:8080");

		ProxyChain chain = ProxyChain.of(file);

		assertTrue(chain.hasProxies());
	}

	@Test
	void hasProxies_falseWhenEmpty() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(
				file, """
						{ "proxies": [] }
						"""
		);

		ProxyChain chain = ProxyChain.of(file);

		assertFalse(chain.hasProxies());
	}

	@Test
	void proxyTag_returnsDirectWhenNoProxies() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(
				file, """
						{ "proxies": [] }
						"""
		);

		ProxyChain chain = ProxyChain.of(file);

		assertEquals("DIRECT", chain.proxyTag());
	}

	@Test
	void proxyTag_returnsHttpSchemeAndHost() throws IOException {
		Path file = writeProxiesFile("http|host1:8080");

		ProxyChain chain = ProxyChain.of(file);

		assertEquals("HTTP] [host1:8080", chain.proxyTag());
	}

	@Test
	void proxyTag_returnsSocks5SchemeAndHost() throws IOException {
		Path file = writeProxiesFile("socks5|host2:1080");

		ProxyChain chain = ProxyChain.of(file);

		assertEquals("SOCKS5] [host2:1080", chain.proxyTag());
	}

	private Path writeProxiesFile(String... entries) throws IOException {
		Path file = tempDir.resolve("proxies.json");
		StringBuilder json = new StringBuilder("{ \"proxies\": [");
		for (int i = 0; i < entries.length; i++) {
			if (i > 0) json.append(",");
			json.append("\"").append(entries[i]).append("\"");
		}
		json.append("] }");
		Files.writeString(file, json.toString());
		return file;
	}
}
