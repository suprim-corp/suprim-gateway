package dev.suprim.gateway.proxy;

import lombok.Builder;

@Builder
public record ProxyEntry(
		Scheme scheme,
		String host,
		int port,
		String username,
		String password
) {

	public enum Scheme {HTTP, SOCKS5}

	public static ProxyEntry parse(String entry) {
		int pipeIndex = entry.indexOf('|');
		if (pipeIndex < 0) {
			throw new IllegalArgumentException(
					"Invalid proxy format, expected 'scheme|[user:pass@]host:port': " +
					entry
			);
		}

		Scheme scheme = parseScheme(entry.substring(0, pipeIndex));
		String rest = entry.substring(pipeIndex + 1);

		String userInfo = null;
		String hostPort;

		if (rest.contains("@")) {
			int atIndex = rest.lastIndexOf('@');
			userInfo = rest.substring(0, atIndex);
			hostPort = rest.substring(atIndex + 1);
		} else {
			hostPort = rest;
		}

		int colonIndex = hostPort.lastIndexOf(':');
		if (colonIndex < 0) {
			throw new IllegalArgumentException(
					"Missing port in proxy entry: " + entry);
		}

		String host = hostPort.substring(0, colonIndex);
		int port;
		try {
			port = Integer.parseInt(hostPort.substring(colonIndex + 1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"Invalid port in proxy entry: " + entry
			);
		}

		String username = null;
		String password = null;
		if (userInfo != null) {
			int colonIdx = userInfo.indexOf(':');
			if (colonIdx >= 0) {
				username = userInfo.substring(0, colonIdx);
				password = userInfo.substring(colonIdx + 1);
			} else {
				username = userInfo;
			}
		}

		return ProxyEntry.builder()
		                 .scheme(scheme)
		                 .host(host)
		                 .port(port)
		                 .username(username)
		                 .password(password)
		                 .build();
	}

	public String maskedUrl() {
		StringBuilder sb = new StringBuilder();
		sb.append(scheme == Scheme.HTTP ? "http" : "socks5").append("|");
		if (username != null) {
			sb.append(username);
			if (password != null) {
				sb.append(":***");
			}
			sb.append('@');
		}
		sb.append(host).append(':').append(port);
		return sb.toString();
	}

	private static Scheme parseScheme(String scheme) {
		return switch (scheme.toLowerCase()) {
			case "http" -> Scheme.HTTP;
			case "socks5" -> Scheme.SOCKS5;
			default -> throw new IllegalArgumentException(
					"Unsupported proxy scheme: " + scheme
			);
		};
	}
}
