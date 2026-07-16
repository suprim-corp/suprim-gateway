package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts InternalRequest to DeepSeek Web API JSON payload.
 */
@Slf4j
public final class DeepSeekRequestConverter {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, String> MODEL_TYPE_MAP = Map.of(
			"v4-flash", "default",
			"v4-pro", "default",
			"v4-flash-search", "default",
			"v4-pro-search", "default",
			"v4-vision", "vision",
			"v4-flash-think", "default",
			"v4-pro-think", "default"
	);

	private DeepSeekRequestConverter() {}

	public static String convert(
			InternalRequest request,
			String chatSessionId
	) {
		String model = stripPrefix(request.model());
		boolean searchEnabled = model.endsWith("-search");
		String modelType = MODEL_TYPE_MAP.getOrDefault(model, "default");

		String prompt = flattenMessages(request.messages());
		if (request.tools() != null && !request.tools().isEmpty()) {
			String toolBlock = injectToolDefinitions(request.tools());
			prompt = toolBlock + "\n\n" + prompt;
			log.debug("[DeepSeek] Injected {} tools into prompt", request.tools().size());
		}

		ObjectNode node = JSON.createObjectNode();
		node.put("chat_session_id", chatSessionId);
		node.put("prompt", prompt);
		node.putNull("parent_message_id");
		node.put("model_type", modelType);
		node.putPOJO("ref_file_ids", List.of());
		node.put("thinking_enabled", true);
		node.put("search_enabled", searchEnabled);
		node.put(
				"temperature",
				Optional.ofNullable(request.temperature()).orElse(0.7)
		);

		return node.toString();
	}

	private static String stripPrefix(String model) {
		if (model.startsWith("deepseek/")) {
			return model.substring("deepseek/".length());
		}
		return model;
	}

	private static String flattenMessages(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return "";
		}
		if (messages.size() == 1) {
			return extractText(messages.getFirst());
		}
		StringBuilder sb = new StringBuilder();
		for (Message msg : messages) {
			String text = extractText(msg);
			if (text.isEmpty()) continue;
			sb.append("[").append(msg.role()).append("]: ").append(text).append(
					"\n\n");
		}
		return sb.toString().trim();
	}

	private static String extractText(Message msg) {
		if (msg.content() == null) {
			return "";
		}
		if (msg.content() instanceof String text) {
			return text;
		}
		return msg.content().toString();
	}

	private static String injectToolDefinitions(List<Tool> tools) {
		List<String> toolNames = tools.stream()
				.map(Tool::function)
				.filter(fn -> fn != null && fn.name() != null)
				.map(Tool.Function::name)
				.toList();

		StringBuilder sb = new StringBuilder();
		sb.append("""
				TOOL CALL FORMAT — FOLLOW EXACTLY:

				<|DSML|tool_calls>
				  <|DSML|invoke name="TOOL_NAME_HERE">
				    <|DSML|parameter name="PARAMETER_NAME"><![CDATA[PARAMETER_VALUE]]></|DSML|parameter>
				  </|DSML|invoke>
				</|DSML|tool_calls>

				RULES:
				1) Use the <|DSML|tool_calls> wrapper format.
				2) Put one or more <|DSML|invoke> entries under a single <|DSML|tool_calls> root.
				3) Put the tool name in the invoke name attribute: <|DSML|invoke name="TOOL_NAME">.
				4) All string values must use <![CDATA[...]]>, even short ones.
				5) Every top-level argument must be a <|DSML|parameter name="ARG_NAME">...</|DSML|parameter> node.
				6) Numbers, booleans, and null stay plain text.
				7) Use only the parameter names in the tool schema. Do not invent fields.
				8) Do NOT wrap XML in markdown fences. Do NOT output explanations after the tool block.
				9) If you call a tool, the first non-whitespace characters of that tool block must be exactly <|DSML|tool_calls>.
				10) Compatibility note: the runtime also accepts the legacy XML tags <tool_calls> / <invoke> / <parameter>.

				""");

		sb.append("AVAILABLE TOOLS:\n\n");
		for (Tool tool : tools) {
			Tool.Function fn = tool.function();
			if (fn == null) continue;
			sb.append("- ").append(fn.name());
			if (fn.description() != null) {
				sb.append(": ").append(fn.description());
			}
			if (fn.parameters() != null) {
				sb.append("\n  Parameters: ").append(fn.parameters());
			}
			sb.append("\n");
		}

		sb.append("\n");
		appendToolExamples(sb, toolNames);
		return sb.toString();
	}

	private static void appendToolExamples(StringBuilder sb, List<String> toolNames) {
		sb.append("【CORRECT EXAMPLE】:\n\n");
		if (toolNames.contains("Read")) {
			sb.append("""
					<|DSML|tool_calls>
					  <|DSML|invoke name="Read">
					    <|DSML|parameter name="file_path"><![CDATA[README.md]]></|DSML|parameter>
					  </|DSML|invoke>
					</|DSML|tool_calls>
					""");
		} else if (toolNames.contains("Bash")) {
			sb.append("""
					<|DSML|tool_calls>
					  <|DSML|invoke name="Bash">
					    <|DSML|parameter name="command"><![CDATA[ls -la]]></|DSML|parameter>
					  </|DSML|invoke>
					</|DSML|tool_calls>
					""");
		} else if (!toolNames.isEmpty()) {
			sb.append("<|DSML|tool_calls>\n");
			sb.append("  <|DSML|invoke name=\"").append(toolNames.getFirst()).append("\">\n");
			sb.append("    <|DSML|parameter name=\"input\"><![CDATA[value]]></|DSML|parameter>\n");
			sb.append("  </|DSML|invoke>\n");
			sb.append("</|DSML|tool_calls>\n");
		}
	}
}
