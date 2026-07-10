package dev.suprim.gateway.proxy;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

public class ProxyClientFactory {

	private ProxyClientFactory() {}

	public static HttpClient build(ProxyEntry entry) {
		HttpClient.Builder builder = HttpClient.newBuilder()
		                                       .connectTimeout(
				                                       Duration.ofSeconds(10)
		                                       );

		if (entry == null) {
			return builder.build();
		}

		ProxySelector proxySelector = createProxySelector(entry);
		builder.proxy(proxySelector);

		if (entry.username() != null && entry.password() != null) {
			builder.authenticator(createAuthenticator(entry));
		}

		return builder.build();
	}

	private static ProxySelector createProxySelector(ProxyEntry entry) {
		Proxy.Type proxyType = entry.scheme() == ProxyEntry.Scheme.HTTP
				? Proxy.Type.HTTP
				: Proxy.Type.SOCKS;

		InetSocketAddress address = InetSocketAddress.createUnresolved(
				entry.host(), entry.port());

		return new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				return List.of(new Proxy(proxyType, address));
			}

			@Override
			public void connectFailed(
					URI uri,
					SocketAddress sa,
					IOException ioe
			) {}
		};
	}

	static Authenticator createAuthenticator(ProxyEntry entry) {
		return new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(
						entry.username(),
						entry.password().toCharArray()
				);
			}
		};
	}
}
