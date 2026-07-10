package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProxyListLoaderTest {

	@TempDir
	Path tempDir;

	@Test
	void load_validFile_returnsList() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, """
				{
				  "proxies": [
				    "http|user:pass@host1:8080",
				    "socks5|host2:1080"
				  ]
				}
				""");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertEquals(2, result.size());
		assertEquals(ProxyEntry.Scheme.HTTP, result.get(0).scheme());
		assertEquals("host1", result.get(0).host());
		assertEquals(ProxyEntry.Scheme.SOCKS5, result.get(1).scheme());
		assertEquals("host2", result.get(1).host());
	}

	@Test
	void load_emptyArray_returnsEmptyList() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, """
				{ "proxies": [] }
				""");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertTrue(result.isEmpty());
	}

	@Test
	void load_missingFile_returnsEmptyList() {
		Path file = tempDir.resolve("nonexistent.json");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertTrue(result.isEmpty());
	}

	@Test
	void load_malformedJson_returnsEmptyList() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, "not valid json {{{");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertTrue(result.isEmpty());
	}

	@Test
	void load_missingProxiesKey_returnsEmptyList() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, """
				{ "other": ["something"] }
				""");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertTrue(result.isEmpty());
	}

	@Test
	void load_proxiesNotArray_returnsEmptyList() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, """
				{ "proxies": "not-an-array" }
				""");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertTrue(result.isEmpty());
	}

	@Test
	void load_ignoresSchemaField() throws IOException {
		Path file = tempDir.resolve("proxies.json");
		Files.writeString(file, """
				{
				  "$schema": "https://json-schema.org/draft/2020-12/schema",
				  "proxies": ["socks5|host:1080"]
				}
				""");

		List<ProxyEntry> result = ProxyListLoader.load(file);

		assertEquals(1, result.size());
		assertEquals("host", result.getFirst().host());
	}
}
