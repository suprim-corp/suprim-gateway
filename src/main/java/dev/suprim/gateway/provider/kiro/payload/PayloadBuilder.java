package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.kiro.utils.ToolConverter;
import dev.suprim.gateway.proxy.ContentExtractor;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Tool;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@Slf4j
public class PayloadBuilder {

	private static final int MAX_PAYLOAD_BYTES = 900_000;
	private final JsonMapper mapper = new JsonMapper();
	private final ModelResolver modelResolver;

	/**
	 * @param profileArn the ARN of the account this payload will be sent as, or null for an
	 *                   API-key account. It must belong to the same account as the bearer token,
	 *                   or the upstream rejects the token — so it is passed per request rather
	 *                   than read from the connected account.
	 */
	public String buildOpenAiPayload(
			InternalRequest request,
			String profileArn
	) throws Exception {
		List<Message> messages = request.messages() != null
				? new ArrayList<>(request.messages())
				: new ArrayList<>();
		String model = request.model();
		List<Tool> tools = request.tools();

		return buildKiroPayload(
				messages,
				model,
				tools,
				profileArn,
				request.clientSessionId(),
				request.temperature(),
				request.topP(),
				request.maxTokens(),
				request.thinking(),
				request.effort()
		);
	}

	private String buildKiroPayload(
			List<Message> messages,
			String model,
			List<Tool> tools,
			String profileArn,
			String clientSessionId,
			Double temperature,
			Double topP,
			Integer maxTokens,
			InternalRequest.Thinking thinking,
			String effort
	) throws Exception {
		String modelId = modelResolver.resolve(model);

		InferenceFields inference = requestFields(
				modelId,
				temperature,
				topP,
				maxTokens,
				thinking,
				effort
		);
		String systemPrompt = withThinkingPrefix(
				extractSystemPrompt(messages),
				inference.fallbackBudget()
		);
		List<Message> nonSystemMessages = filterNonSystem(messages);

		boolean toolsEnabled = tools != null && !tools.isEmpty();
		HistoryBuilder.HistoryResult historyResult = HistoryBuilder.build(
				nonSystemMessages,
				modelId,
				toolsEnabled
		);
		ArrayNode history = historyResult.history();

		ObjectNode userInputMessage = historyResult.currentMessage();
		ObjectNode historyStart = firstUserMessage(history);
		ObjectNode sessionStart = (historyStart == null
				? userInputMessage
				: historyStart).deepCopy();
		prefixSystemPrompt(sessionStart, systemPrompt);
		KiroSessionReplay.ReplayState replay = KiroSessionReplay.resolve(
				clientSessionId,
				modelId,
				systemPrompt,
				sessionStart
		);
		if (replay.created()) {
			if (historyStart == null) {
				userInputMessage = sessionStart;
			} else {
				historyStart.setAll(sessionStart);
			}
		}
		ObjectNode root = buildRoot(
				history,
				userInputMessage,
				tools, profileArn, systemPrompt, replay
		);
		attachInference(root, inference);

		return truncatePayload(root, history, !replay.created());
	}

	private ObjectNode buildRoot(
			ArrayNode history,
			ObjectNode userInputMessage,
			List<Tool> tools,
			String profileArn,
			String systemPrompt,
			KiroSessionReplay.ReplayState replay
	) {
		ObjectNode root = mapper.createObjectNode();
		ObjectNode conversationState = root.putObject("conversationState");
		conversationState.put("chatTriggerType", "MANUAL");
		conversationState.putObject("currentMessage").set(
				"userInputMessage",
				userInputMessage
		);

		KiroSessionReplay.SessionState session = replay.session();
		conversationState.put("conversationId", session.ids().conversationId());
		conversationState.put(
				"agentContinuationId",
				session.ids().continuationId()
		);
		conversationState.put("agentTaskType", "vibe");
		root.put("agentMode", "vibe");
		if (!systemPrompt.isEmpty()) {
			root.put("systemPrompt", systemPrompt);
		}

		replaySessionStart(history, userInputMessage, replay);
		attachTools(userInputMessage, tools);

		conversationState.set("history", history);

		if (profileArn != null && !profileArn.isBlank()) {
			root.put("profileArn", profileArn);
		}

		return root;
	}

	private static void replaySessionStart(
			ArrayNode history,
			ObjectNode currentMessage,
			KiroSessionReplay.ReplayState replay
	) {
		if (replay.created()) {
			return;
		}

		ObjectNode frozenStart = replay.session().frozenSessionStart();
		if (history.isEmpty()) {
			history.addObject().set("userInputMessage", frozenStart);
		} else if (history.get(0).has("userInputMessage")) {
			((ObjectNode) history.get(0)).set("userInputMessage", frozenStart);
		} else {
			history.insertObject(0).set("userInputMessage", frozenStart);
		}

		if (currentMessage.equals(frozenStart)) {
			history.remove(0);
		}
	}

	private void attachTools(ObjectNode userInputMessage, List<Tool> tools) {
		if (tools == null || tools.isEmpty()) {
			return;
		}

		ObjectNode context = userInputMessage.has("userInputMessageContext")
				? (ObjectNode) userInputMessage.get("userInputMessageContext")
				: userInputMessage.putObject("userInputMessageContext");
		ArrayNode toolsNode = context.putArray("tools");
		for (JsonNode tool : mapper.valueToTree(ToolConverter.convert(tools))) {
			toolsNode.add(tool);
		}
	}

	private static InferenceFields requestFields(
			String modelId,
			Double temperature,
			Double topP,
			Integer maxTokens,
			InternalRequest.Thinking thinking,
			String effort
	) {
		String effortPath = effortPath(modelId);
		String normalizedEffort = normalizeEffort(effort, effortPath);
		Integer fallbackBudget = thinkingBudget(thinking);
		if (normalizedEffort != null && effortPath != null) {
			fallbackBudget = null;
		}
		boolean thinkingEnabled = fallbackBudget != null || normalizedEffort != null;
		return InferenceFields.builder()
		                      .temperature(thinkingEnabled ? null : temperature)
		                      .topP(thinkingEnabled ? null : topP)
		                      .maxTokens(positive(maxTokens))
		                      .effortPath(effortPath)
		                      .effort(normalizedEffort)
		                      .fallbackBudget(fallbackBudget)
		                      .build();
	}

	private static void attachInference(ObjectNode root, InferenceFields fields) {
		if (fields.maxTokens() != null || fields.temperature() != null ||
		    fields.topP() != null) {
			ObjectNode inference = root.putObject("inferenceConfig");
			if (fields.maxTokens() != null) {
				inference.put("maxTokens", fields.maxTokens());
			}
			if (fields.temperature() != null) {
				inference.put("temperature", fields.temperature());
			}
			if (fields.topP() != null) {
				inference.put("topP", fields.topP());
			}
		}

		if (fields.effortPath() == null || fields.effort() == null) {
			return;
		}
		ObjectNode additional = root.putObject("additionalModelRequestFields");
		if ("reasoning".equals(fields.effortPath())) {
			additional.putObject("reasoning").put("effort", fields.effort());
			return;
		}
		additional.putObject("thinking")
		          .put("type", "adaptive")
		          .put("display", "summarized");
		additional.putObject("output_config").put("effort", fields.effort());
	}

	private static String withThinkingPrefix(String systemPrompt, Integer budget) {
		if (budget == null) {
			return systemPrompt;
		}
		String prefix = "<thinking_mode>enabled</thinking_mode>\n" +
		                "<max_thinking_length>" + budget +
		                "</max_thinking_length>";
		if (systemPrompt.contains("<thinking_mode>")) {
			return systemPrompt;
		}
		return systemPrompt.isEmpty() ? prefix : prefix + "\n" + systemPrompt;
	}

	private static Integer thinkingBudget(InternalRequest.Thinking thinking) {
		if (thinking == null || thinking.type() == null) {
			return null;
		}
		String type = thinking.type().trim().toLowerCase();
		if (Set.of("disabled", "none", "off").contains(type)) {
			return null;
		}
		Integer budget = thinking.budgetTokens();
		if (budget == null) {
			return 16_000;
		}
		return Math.max(1, Math.min(32_000, budget));
	}

	private static Integer positive(Integer value) {
		return value != null && value > 0 ? value : null;
	}

	private static String normalizeEffort(String effort, String effortPath) {
		if (effort == null || effort.isBlank() || effortPath == null) {
			return null;
		}
		String normalized = effort.trim().toLowerCase();
		if (Set.of("none", "off", "disabled").contains(normalized)) {
			return null;
		}
		if ("reasoning".equals(effortPath)) {
			if ("max".equals(normalized)) {
				return "xhigh";
			}
			return Set.of("low", "medium", "high", "xhigh").contains(normalized)
					? normalized
					: null;
		}
		if (Set.of("xhigh", "max").contains(normalized)) {
			return "high";
		}
		return Set.of("low", "medium", "high").contains(normalized)
				? normalized
				: null;
	}

	private static String effortPath(String modelId) {
		String normalized = modelId.toLowerCase().replace('-', '.');
		if (Pattern.compile(
				"(?:^|[/.])gpt[/.]5[/.]6(?:[/.]|$)"
		).matcher(normalized).find()) {
			return "reasoning";
		}
		if (!normalized.contains("claude")) {
			return null;
		}
		Matcher matcher = Pattern.compile(
				"(?:^|[/.])claude(?:[/.][a-z]+)*[/.](\\d+)(?:[/.](\\d+))?(?:[/.]|$)"
		).matcher(normalized);
		if (!matcher.find()) {
			return null;
		}
		int major = Integer.parseInt(matcher.group(1));
		String minorText = matcher.group(2);
		if (major < 4) {
			return null;
		}
		if (major > 4) {
			return "output_config";
		}
		if (minorText == null) {
			return null;
		}
		int minor = Integer.parseInt(minorText);
		return minor > 5 && minor < 1_000 ? "output_config" : null;
	}

	@Builder
	private record InferenceFields(
			Double temperature,
			Double topP,
			Integer maxTokens,
			String effortPath,
			String effort,
			Integer fallbackBudget
	) {}

	private String truncatePayload(
			ObjectNode root,
			ArrayNode history,
			boolean protectSessionStart
	) throws Exception {
		ObjectNode conversationState = (ObjectNode) root.get("conversationState");
		String json = mapper.writeValueAsString(root);

		boolean hasTools = !root.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools"
		).isMissingNode();
		boolean hasToolResults = !root.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/toolResults"
		).isMissingNode();

		log.debug(
				"[Payload] model={}, size={}, history={}, hasTools={}, hasToolResults={}, hasSystemPrompt={}, hasProfileArn={}",
				root.at("/conversationState/currentMessage/userInputMessage/modelId")
				    .asString(),
				payloadBytes(json),
				history.size(),
				hasTools,
				hasToolResults,
				root.has("systemPrompt"),
				root.has("profileArn")
		);

		validateHistory(history);

		while (payloadBytes(json) > MAX_PAYLOAD_BYTES && removeOldestTurn(
				history,
				protectSessionStart
		)) {
			json = mapper.writeValueAsString(root);
		}

		validateHistory(history);
		if (payloadBytes(json) > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException(
					"Kiro payload exceeds upstream size limit");
		}
		KiroPayloadDiagnostics.log(root);
		return json;
	}

	private static int payloadBytes(String payload) {
		return payload.getBytes(StandardCharsets.UTF_8).length;
	}

	private static ObjectNode firstUserMessage(ArrayNode history) {
		for (JsonNode entry : history) {
			if (entry.has("userInputMessage")) {
				return (ObjectNode) entry.get("userInputMessage");
			}
		}
		return null;
	}

	private static void prefixSystemPrompt(
			ObjectNode userInputMessage,
			String systemPrompt
	) {
		if (systemPrompt.isEmpty()) {
			return;
		}
		String content = userInputMessage.path("content").asString();
		userInputMessage.put(
				"content",
				content.isEmpty() ? systemPrompt : systemPrompt + "\n\n" + content
		);
	}

	private static String extractSystemPrompt(List<Message> messages) {
		StringBuilder systemPrompt = new StringBuilder();
		for (Message msg : messages) {
			if ("system".equals(msg.role())) {
				String text = ContentExtractor.fromMessage(msg);
				if (text != null && !text.isEmpty()) {
					if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
					systemPrompt.append(text);
				}
			}
		}
		return systemPrompt.toString().trim();
	}

	private static List<Message> filterNonSystem(List<Message> messages) {
		List<Message> result = new ArrayList<>();
		for (Message msg : messages) {
			if (!"system".equals(msg.role())) {
				result.add(msg);
			}
		}
		return result;
	}

	private static boolean removeOldestTurn(
			ArrayNode history,
			boolean protectSessionStart
	) {
		int start = protectSessionStart ? 1 : 0;
		if (history.size() <= start) {
			return false;
		}

		int count = history.get(start).has("userInputMessage") ? 1 : 0;
		if (protectSessionStart &&
		    history.get(start).has("assistantResponseMessage")) {
			count = 1;
			if (start + count < history.size() &&
			    history.get(start + count).has("userInputMessage")) {
				count++;
			}
		} else if (start + count < history.size() &&
		           history.get(start + count).has("assistantResponseMessage")) {
			count++;
			if (start + count < history.size() &&
			    hasToolResults(history.get(start + count))) {
				count++;
			}
		}
		if (count == 0) {
			count = 1;
		}
		for (int i = 0; i < count; i++) {
			history.remove(start);
		}
		return true;
	}

	private static boolean hasToolResults(JsonNode entry) {
		return entry.at(
				"/userInputMessage/userInputMessageContext/toolResults"
		).isArray();
	}

	private static void validateHistory(ArrayNode history) {
		boolean expectUser = !history.isEmpty() && history.get(0).has(
				"userInputMessage"
		);
		Set<String> availableToolUses = new HashSet<>();
		for (JsonNode entry : history) {
			boolean user = entry.has("userInputMessage");
			if (user != expectUser) {
				throw new IllegalArgumentException(
						"Kiro history roles must alternate"
				);
			}
			expectUser = !expectUser;

			JsonNode userMessage = entry.get("userInputMessage");
			if (userMessage != null) {
				if (!userMessage.hasNonNull("modelId")) {
					throw new IllegalArgumentException(
							"Kiro history user message requires modelId"
					);
				}
				JsonNode tools = userMessage.at("/userInputMessageContext/tools");
				if (!tools.isMissingNode()) {
					throw new IllegalArgumentException(
							"Kiro history cannot contain tool definitions"
					);
				}
				JsonNode results = userMessage.at(
						"/userInputMessageContext/toolResults"
				);
				if (results.isArray()) {
					for (JsonNode result : results) {
						if (!availableToolUses.remove(
								result.path("toolUseId")
								      .asString()
						)
						) {
							throw new IllegalArgumentException(
									"Kiro tool result has no matching tool use"
							);
						}
					}
				}
				continue;
			}

			availableToolUses.clear();
			JsonNode uses = entry.at("/assistantResponseMessage/toolUses");
			if (uses.isArray()) {
				for (JsonNode use : uses) {
					String id = use.path("toolUseId").asString();
					if (!id.isBlank()) {
						availableToolUses.add(id);
					}
				}
			}
		}
	}
}
