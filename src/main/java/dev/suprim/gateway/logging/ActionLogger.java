package dev.suprim.gateway.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ActionLogger {

	private static final Logger log = LoggerFactory.getLogger(ActionLogger.class);
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final int MAX_BODY_LENGTH = 500;
	private static final Set<String> SENSITIVE_FIELDS = Set.of(
			"password", "token", "secret", "api_key", "apikey",
			"authorization", "refresh_token", "access_token", "credentials"
	);

	private static final Set<String> SENSITIVE_PARAMS = Set.of(
			"key", "token", "secret", "password", "api_key", "apikey",
			"createdkey", "access_token", "refresh_token", "authorization"
	);

	private static final String RESET = "[0m";
	private static final String CYAN = "[36m";
	private static final String GREEN = "[32m";
	private static final String YELLOW = "[33m";
	private static final String RED = "[31m";
	private static final String MAGENTA = "[35m";

	private ActionLogger() {}

	public static void logRequest(
			String method,
			String uri,
			String ip,
			String query,
			String body
	) {
		StringBuilder sb = new StringBuilder();
		sb.append(CYAN)
		  .append("→ ")
		  .append(method)
		  .append(" ")
		  .append(uri)
		  .append(RESET);
		if (query != null && !query.isEmpty()) {
			sb.append("?").append(maskQueryParams(query));
		}
		sb.append(" from ").append(MAGENTA).append(ip).append(RESET);
		if (body != null && !body.isBlank()) {
			sb.append("\n  body: ").append(maskSensitive(truncate(body)));
		}
		log.info(sb.toString());
	}

	public static void logResponse(
			String method,
			String uri,
			int status,
			String body,
			long durationMs,
			String authInfo
	) {
		String color = status < 400 ? GREEN : status < 500 ? YELLOW : RED;
		StringBuilder sb = new StringBuilder();
		sb.append(color).append("← ").append(status).append(RESET);
		sb.append(" ").append(method).append(" ").append(uri);
		sb.append(" (").append(durationMs).append("ms)");
		if (authInfo != null && !authInfo.isEmpty()) {
			sb.append(" [").append(authInfo).append("]");
		}
		if (body != null && !body.isBlank()) {
			sb.append("\n  response: ").append(summarizeJson(body));
		}
		log.info(sb.toString());
	}

	private static String truncate(String text) {
		if (text.length() <= MAX_BODY_LENGTH) {
			return text;
		}
		return text.substring(0, MAX_BODY_LENGTH) + "...[truncated]";
	}

	private static String maskSensitive(String body) {
		String result = body;
		for (String field : SENSITIVE_FIELDS) {
			result = result.replaceAll(
					"(?i)(\"" + field + "\"\\s*:\\s*\")([^\"]*)(\")",
					"$1***$3"
			);
		}
		return result;
	}

	private static String maskQueryParams(String query) {
		String[] pairs = query.split("&");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pairs.length; i++) {
			if (i > 0) sb.append("&");
			int eq = pairs[i].indexOf('=');
			if (eq > 0) {
				String name = pairs[i].substring(0, eq);
				if (SENSITIVE_PARAMS.contains(name.toLowerCase())) {
					sb.append(name).append("=***");
				} else {
					sb.append(pairs[i]);
				}
			} else {
				sb.append(pairs[i]);
			}
		}
		return sb.toString();
	}

	@SuppressWarnings("try")
	@lombok.Generated
	static String summarizeJson(String json) {
		if (json == null || json.isBlank()) {
			return "";
		}
		try (JsonParser parser = MAPPER.createParser(json)) {
			JsonToken token = parser.nextToken();
			if (token == JsonToken.START_OBJECT) {
				return summarizeObject(parser);
			} else if (token == JsonToken.START_ARRAY) {
				return summarizeArray(parser);
			}
		} catch (Exception e) {
			// not valid JSON
		}
		return truncate(json);
	}

	private static String summarizeObject(JsonParser parser) throws Exception {
		List<String> keys = new ArrayList<>();
		int depth = 1;
		while (depth > 0) {
			JsonToken token = parser.nextToken();
			if (token == JsonToken.PROPERTY_NAME && depth == 1) {
				keys.add(parser.currentName());
			} else if (token == JsonToken.START_OBJECT ||
			           token == JsonToken.START_ARRAY) {
				depth++;
			} else if (token == JsonToken.END_OBJECT ||
			           token == JsonToken.END_ARRAY) {
				depth--;
			}
		}
		return "{" + String.join(", ", keys) + "}";
	}

	private static String summarizeArray(JsonParser parser) throws Exception {
		int count = 0;
		int depth = 1;
		while (depth > 0) {
			JsonToken token = parser.nextToken();
			if (token == JsonToken.START_OBJECT ||
			    token == JsonToken.START_ARRAY) {
				if (depth == 1) count++;
				depth++;
			} else if (token == JsonToken.END_OBJECT ||
			           token == JsonToken.END_ARRAY) {
				depth--;
			} else if (depth == 1) {
				count++;
			}
		}
		return "List[" + count + " items]";
	}
}
