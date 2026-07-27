package dev.suprim.gateway.provider.kiro;

import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Error text from a failed control-plane response.
 */
final class KiroErrors {

	private KiroErrors() {}

	/**
	 * The upstream's own {@code message}, falling back to the status code when the body is
	 * absent, unparseable, or carries no message. Callers surface this to the operator, so a
	 * status code is more useful than an empty string.
	 */
	static String message(HttpResponse<String> response) {
		try {
			Object message = new JsonMapper()
					.readValue(response.body(), Map.class)
					.get("message");
			if (message instanceof String text && !text.isBlank()) {
				return text;
			}
		} catch (Exception ignored) {}
		return "HTTP " + response.statusCode();
	}
}
