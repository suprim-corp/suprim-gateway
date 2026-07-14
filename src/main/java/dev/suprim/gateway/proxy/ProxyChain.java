package dev.suprim.gateway.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class ProxyChain implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(ProxyChain.class);

	private final ProxyRotator rotator;
	private HttpClient currentClient;

	private ProxyChain(ProxyRotator rotator) {
		this.rotator = rotator;
		this.currentClient = ProxyClientFactory.build(rotator.current());
	}

	public static ProxyChain of(Path proxyFile) {
		List<ProxyEntry> entries = ProxyListLoader.load(proxyFile);
		ProxyRotator rotator = ProxyRotator.of(entries);

		if (entries.isEmpty()) {
			log.info("[Proxy] No proxies configured, using direct connection");
		} else {
			String masked = entries.stream()
			                       .map(ProxyEntry::maskedUrl)
			                       .collect(Collectors.joining(", "));
			log.info("[Proxy] Loaded {} proxies: [{}]", entries.size(), masked);
			log.info("[Proxy] Active: {}", entries.getFirst().maskedUrl());
		}

		return new ProxyChain(rotator);
	}

	public HttpClient currentClient() {
		if (rotator.isExhausted()) {
			return null;
		}
		return currentClient;
	}

	public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
		if (rotator.isExhausted() || currentClient == null) {
			throw new IOException("No available proxy client");
		}
		return currentClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	public void onFailure() {
		ProxyEntry failed = rotator.current();
		rotator.advance();

		if (rotator.isExhausted()) {
			log.error("[Proxy] All proxies exhausted, request will fail");
			closeClient();
			return;
		}

		ProxyEntry next = rotator.current();
		log.warn(
				"[Proxy] {} failed, switching to {}",
				failed.maskedUrl(),
				next.maskedUrl()
		);
		closeClient();
		currentClient = ProxyClientFactory.build(next);
	}

	public void resetAttempts() {
		rotator.resetAttempts();
		closeClient();
		currentClient = ProxyClientFactory.build(rotator.current());
	}

	public boolean hasProxies() {
		return rotator.size() > 0;
	}

	public String proxyTag() {
		ProxyEntry entry = rotator.current();
		if (entry == null) {
			return "DIRECT";
		}
		String scheme = entry.scheme() == ProxyEntry.Scheme.HTTP ? "HTTP" : "SOCKS5";
		return scheme + "] [" + entry.host() + ":" + entry.port();
	}

	private void closeClient() {
		if (currentClient != null) {
			currentClient.close();
			currentClient = null;
		}
	}

	@Override
	public void close() {
		closeClient();
	}
}
