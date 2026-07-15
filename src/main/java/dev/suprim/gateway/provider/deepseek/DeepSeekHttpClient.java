package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.ProxyEntry;

import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

/**
 * HTTP client for chat.deepseek.com with iOS TLS fingerprinting via impersonator.
 */
public class DeepSeekHttpClient {

	private static final MediaType JSON = MediaType.get("application/json");
	private static final String USER_AGENT = "DeepSeek/2.0.4 Android/35";

	private final OkHttpClient client;

	public DeepSeekHttpClient(ProxyEntry proxyEntry) {
		if (proxyEntry != null && proxyEntry.username() != null &&
		    proxyEntry.password() != null) {
			java.net.Authenticator.setDefault(new java.net.Authenticator() {
				@Override
				protected java.net.PasswordAuthentication getPasswordAuthentication() {
					if (getRequestingHost().equals(proxyEntry.host()) &&
					    getRequestingPort() == proxyEntry.port()) {
						return new java.net.PasswordAuthentication(
								proxyEntry.username(),
								proxyEntry.password().toCharArray()
						);
					}
					return null;
				}
			});
		}

		OkHttpClient.Builder builder = new OkHttpClient.Builder()
				.protocols(List.of(okhttp3.Protocol.HTTP_1_1));

		if (proxyEntry != null) {
			Proxy.Type type;

			if (proxyEntry.scheme() == ProxyEntry.Scheme.SOCKS5) {
				type = Proxy.Type.SOCKS;
			} else {
				type = Proxy.Type.HTTP;
			}

			Proxy proxy = new Proxy(
					type,
					new InetSocketAddress(proxyEntry.host(), proxyEntry.port())
			);
			builder.proxy(proxy);

			if (
					proxyEntry.scheme() == ProxyEntry.Scheme.HTTP
					&& proxyEntry.username() != null &&
					proxyEntry.password() != null
			) {
				builder.proxyAuthenticator(
						(route, response) -> {
							String credential = Credentials.basic(
									proxyEntry.username(), proxyEntry.password()
							);
							return response.request().newBuilder()
							               .header(
									               "Proxy-Authorization",
									               credential
							               )
							               .build();
						}
				);
			}
		}

		this.client = builder.build();
	}

	public OkHttpClient rawClient() {
		return client;
	}

	public Request buildPostRequest(
			String url,
			String body,
			String token,
			String powHeader
	) {
		Request.Builder builder = new Request.Builder()
				.url(url)
				.post(RequestBody.create(body, JSON))
				.header("Host", "chat.deepseek.com")
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.header("accept-charset", "UTF-8")
				.header("User-Agent", USER_AGENT)
				.header("x-client-platform", "android")
				.header("x-client-version", "2.0.4")
				.header("x-client-locale", "zh_CN");

		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (powHeader != null) {
			builder.header("x-ds-pow-response", powHeader);
		}

		return builder.build();
	}

	public Response execute(Request request) throws IOException {
		return client.newCall(request).execute();
	}

	public InputStream executeStream(Request request) throws IOException {
		Response response = client.newCall(request).execute();
		return extractBody(response);
	}

	InputStream extractBody(Response response) throws IOException {
		if (response.body() == null) {
			response.close();
			throw new IOException("Empty response body from DeepSeek");
		}
		return response.body().byteStream();
	}
}
