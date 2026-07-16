package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming tool sieve that buffers content, detects XML tool_calls markup,
 * and emits either content or tool_use KiroEvents.
 */
@Slf4j
public class DeepSeekToolSieve {

	private static final Pattern TOOL_CALLS_OPEN = Pattern.compile(
			"<[^>]*tool_calls[^>]*>", Pattern.CASE_INSENSITIVE
	);
	private static final Pattern TOOL_CALLS_CLOSE = Pattern.compile(
			"</[^>]*tool_calls[^>]*>", Pattern.CASE_INSENSITIVE
	);
	private static final Pattern INVOKE_PATTERN = Pattern.compile(
			"<[^>]*invoke[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</[^>]*invoke[^>]*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL
	);
	private static final Pattern PARAMETER_PATTERN = Pattern.compile(
			"<[^>]*parameter[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</[^>]*parameter[^>]*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL
	);
	private static final Pattern CDATA_PATTERN = Pattern.compile(
			"<!\\[CDATA\\[(.*?)(?:\\]\\]>|\\]\\]\\]\\]><!\\[CDATA\\[>)", Pattern.DOTALL
	);
	private static final Pattern JSON_CODE_BLOCK = Pattern.compile(
			"```(?:json)?\\s*\\n(\\[.*?])\\s*\\n```", Pattern.DOTALL
	);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern LOOSE_TOOL_CALL = Pattern.compile(
			"^\\s*([A-Z][a-zA-Z]+)\\s+\"(.+)\"\\s*$"
	);
	private static final Pattern LOOSE_TOOL_CALL_MULTILINE = Pattern.compile(
			"^\\s*([A-Z][a-zA-Z]+)\\s+\"(.*?)\"\\s*$", Pattern.DOTALL
	);

	private final StringBuilder buffer = new StringBuilder();
	private final StringBuilder fullContent = new StringBuilder();
	private final Consumer<KiroEvent> downstream;
	private final Set<String> toolNames;
	private boolean capturing = false;

	public DeepSeekToolSieve(Consumer<KiroEvent> downstream, Set<String> toolNames) {
		this.downstream = downstream;
		this.toolNames = toolNames;
	}

	public void accept(KiroEvent event) {
		if (!"content".equals(event.type())) {
			downstream.accept(event);
			return;
		}

		fullContent.append(event.content());
		buffer.append(event.content());
		processBuffer(false);
	}

	public void flush() {
		processBuffer(true);
		if (!buffer.isEmpty()) {
			downstream.accept(KiroEvent.content(buffer.toString()));
			buffer.setLength(0);
		}
		if (!fullContent.isEmpty()) {
			String content = fullContent.toString();
			boolean hasToolMarkup = content.contains("tool_calls") || content.contains("invoke");
			if (!hasToolMarkup) {
				tryParseFallbackToolCalls(content);
			}
			log.debug(
					"[DeepSeek] ToolSieve content len={}, hasToolMarkup={}, content={}",
					content.length(),
					hasToolMarkup,
					content
			);
		}
	}

	private void tryParseFallbackToolCalls(String content) {
		// Try JSON code block format
		Matcher jsonMatcher = JSON_CODE_BLOCK.matcher(content);
		if (jsonMatcher.find()) {
			if (parseJsonArray(jsonMatcher.group(1))) return;
		}

		// Try loose format: ToolName "argument"
		String trimmed = content.trim();
		Matcher looseMatcher = LOOSE_TOOL_CALL.matcher(trimmed);
		if (looseMatcher.matches()) {
			String name = looseMatcher.group(1);
			String arg = looseMatcher.group(2);
			if (toolNames.contains(name)) {
				String input = guessInputParam(name, arg);
				String toolId = "call_" + UUID.randomUUID().toString().substring(0, 8);
				downstream.accept(KiroEvent.toolUse(name, input, toolId));
				return;
			}
		}

		// Try multi-line loose: entire content is one tool call
		Matcher multiMatcher = LOOSE_TOOL_CALL_MULTILINE.matcher(trimmed);
		if (multiMatcher.matches()) {
			String name = multiMatcher.group(1);
			String arg = multiMatcher.group(2);
			if (toolNames.contains(name)) {
				String input = guessInputParam(name, arg);
				String toolId = "call_" + UUID.randomUUID().toString().substring(0, 8);
				downstream.accept(KiroEvent.toolUse(name, input, toolId));
			}
		}
	}

	private boolean parseJsonArray(String jsonArray) {
		try {
			tools.jackson.databind.JsonNode root = JSON.readTree(jsonArray);
			if (!root.isArray()) return false;
			boolean emitted = false;
			for (tools.jackson.databind.JsonNode item : root) {
				String name = item.has("name") ? item.get("name").asString("") : "";
				if (name.isEmpty()) continue;
				tools.jackson.databind.JsonNode params = item.has("params") ? item.get("params") :
						item.has("parameters") ? item.get("parameters") :
						item.has("input") ? item.get("input") : null;
				String input = params != null ? params.toString() : "{}";
				String toolId = "call_" + UUID.randomUUID().toString().substring(0, 8);
				downstream.accept(KiroEvent.toolUse(name, input, toolId));
				emitted = true;
			}
			return emitted;
		} catch (Exception e) {
			log.debug("[DeepSeek] ToolSieve JSON parse failed: {}", e.getMessage());
			return false;
		}
	}

	private String guessInputParam(String toolName, String value) {
		String paramName = switch (toolName) {
			case "Bash" -> "command";
			case "Read", "Write" -> "file_path";
			case "Glob" -> "pattern";
			case "Grep" -> "query";
			default -> "input";
		};
		try {
			return JSON.writeValueAsString(Map.of(paramName, value));
		} catch (Exception e) {
			return "{}";
		}
	}

	private void processBuffer(boolean finalFlush) {
		while (true) {
			String text = buffer.toString();

			if (!capturing) {
				Matcher openMatcher = TOOL_CALLS_OPEN.matcher(text);
				if (openMatcher.find()) {
					String before = text.substring(0, openMatcher.start());
					if (!before.isEmpty()) {
						downstream.accept(KiroEvent.content(before));
					}
					buffer.setLength(0);
					buffer.append(text.substring(openMatcher.start()));
					capturing = true;
					continue;
				}

				if (!finalFlush && couldBePartialTag(text)) {
					int safeEnd = findSafeFlushPoint(text);
					if (safeEnd > 0) {
						downstream.accept(
								KiroEvent.content(
										text.substring(
												0,
												safeEnd
										)
								)
						);
						buffer.delete(0, safeEnd);
					}
					return;
				}

				if (!text.isEmpty()) {
					downstream.accept(KiroEvent.content(text));
					buffer.setLength(0);
				}
				return;
			}

			Matcher closeMatcher = TOOL_CALLS_CLOSE.matcher(text);
			if (closeMatcher.find()) {
				String toolBlock = text.substring(0, closeMatcher.end());
				String remainder = text.substring(closeMatcher.end());
				buffer.setLength(0);
				buffer.append(remainder);
				capturing = false;

				List<ToolCall> calls = parseToolCalls(toolBlock);
				for (ToolCall call : calls) {
					String toolId =
							"call_" + UUID.randomUUID().toString().substring(
									0,
									8
							);
					String input = serializeInput(call.parameters());
					downstream.accept(
							KiroEvent.toolUse(
									call.name(),
									input,
									toolId
							)
					);
				}
				continue;
			}

			if (finalFlush) {
				downstream.accept(KiroEvent.content(text));
				buffer.setLength(0);
				capturing = false;
			}
			return;
		}
	}

	private boolean couldBePartialTag(String text) {
		int lastLt = text.lastIndexOf('<');
		return lastLt >= 0 && lastLt > text.length() - 50;
	}

	private int findSafeFlushPoint(String text) {
		int lastLt = text.lastIndexOf('<');
		return Math.max(lastLt, 0);
	}

	private List<ToolCall> parseToolCalls(String block) {
		List<ToolCall> calls = new ArrayList<>();
		Matcher invokeMatcher = INVOKE_PATTERN.matcher(block);
		while (invokeMatcher.find()) {
			String name = invokeMatcher.group(1);
			String body = invokeMatcher.group(2);
			Map<String, String> params = new LinkedHashMap<>();
			Matcher paramMatcher = PARAMETER_PATTERN.matcher(body);
			while (paramMatcher.find()) {
				String paramName = paramMatcher.group(1);
				String paramValue = extractCDATA(paramMatcher.group(2));
				params.put(paramName, paramValue);
			}
			calls.add(ToolCall.builder().name(name).parameters(params).build());
		}
		return calls;
	}

	private String extractCDATA(String value) {
		Matcher cdataMatcher = CDATA_PATTERN.matcher(value);
		if (cdataMatcher.find()) {
			return cdataMatcher.group(1);
		}
		return value.trim();
	}

	private String serializeInput(Map<String, String> params) {
		if (params.isEmpty()) {
			return "{}";
		}
		try {
			return JSON.writeValueAsString(params);
		} catch (Exception e) {
			return "{}";
		}
	}

	@Builder
	private record ToolCall(String name, Map<String, String> parameters) {}
}
