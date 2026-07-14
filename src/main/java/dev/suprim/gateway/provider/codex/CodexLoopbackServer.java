package dev.suprim.gateway.provider.codex;

import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.function.Consumer;

@Slf4j
@Component
public class CodexLoopbackServer {

	private static final int PORT = 1455;
	private static final String CALLBACK_PATH = "/auth/callback";

	private HttpServer server;

	public synchronized void start(String codeVerifier, String expectedState, String gatewayBase, Consumer<String> onCode) {
		stop();
		try {
			server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
			server.createContext(CALLBACK_PATH, exchange -> {
				String query = exchange.getRequestURI().getQuery();
				String code = extractParam(query, "code");
				String state = extractParam(query, "state");

				if (code == null || !expectedState.equals(state)) {
					exchange.getResponseHeaders().set("Location", gatewayBase + "/providers?error=state_mismatch");
					exchange.sendResponseHeaders(302, -1);
					exchange.close();
					stopAsync();
					return;
				}

				try {
					onCode.accept(code);
					exchange.getResponseHeaders().set("Location", gatewayBase + "/providers?codex=connected");
					exchange.sendResponseHeaders(302, -1);
					exchange.close();
				} catch (Exception e) {
					log.error("[Codex] Loopback callback error: {}", e.getMessage());
					exchange.getResponseHeaders().set("Location", gatewayBase + "/providers?error=codex_auth_failed");
					exchange.sendResponseHeaders(302, -1);
					exchange.close();
				} finally {
					stopAsync();
				}
			});
			server.setExecutor(null);
			server.start();
			log.info("[Codex] Loopback server started on port {}", PORT);
		} catch (IOException e) {
			log.error("[Codex] Failed to start loopback server on port {}: {}", PORT, e.getMessage());
			throw new RuntimeException("Cannot bind loopback port " + PORT, e);
		}
	}

	public synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
			log.debug("[Codex] Loopback server stopped");
		}
	}

	private void stopAsync() {
		new Thread(() -> {
			try { Thread.sleep(500); } catch (InterruptedException ignored) {}
			stop();
		}).start();
	}

	private static String extractParam(String query, String name) {
		if (query == null) return null;
		for (String param : query.split("&")) {
			String[] kv = param.split("=", 2);
			if (kv.length == 2 && kv[0].equals(name)) {
				return URI.create("http://x/?" + param).getQuery().substring(name.length() + 1);
			}
		}
		return null;
	}
}
