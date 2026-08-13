package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadBuilderTest {

	@AfterEach
	void clearSessions() {
		KiroSessionReplay.clear();
	}

	@Test
	void firstSystemRequestUsesKiroAgentPayloadWithoutSyntheticHistory() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("session-1")
		                                         .messages(List.of(
				                                         Message.of("system", "Follow instructions"),
				                                         Message.of("user", "Hello")
		                                         ))
		                                         .build();

		JsonNode payload = new JsonMapper().readTree(
				builder.buildOpenAiPayload(request, "arn:test")
		);
		JsonNode conversation = payload.get("conversationState");

		assertFalse(payload.has("systemPrompt"));
		assertEquals("vibe", payload.get("agentMode").asString());
		assertEquals("vibe", conversation.get("agentTaskType").asString());
		assertTrue(!conversation.get("conversationId").asString().isBlank());
		assertTrue(!conversation.get("agentContinuationId").asString().isBlank());
		assertTrue(conversation.get("history").isEmpty());
		assertEquals(
				"Follow instructions\n\nHello",
				conversation.at("/currentMessage/userInputMessage/content").asString()
		);
	}

	@Test
	void buildsClaudeCodePayloadContract() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode parameters = new JsonMapper().readTree("""
				{
				  "properties": {
				    "path": {"type": "string", "additionalProperties": false}
				  },
				  "required": ["path"],
				  "additionalProperties": false
				}
				""");
		Tool tool = Tool.builder()
		                .type("function")
		                .function(Tool.Function.builder()
		                                       .name("read_file")
		                                       .parameters(parameters)
		                                       .build())
		                .build();
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("claude-code-session")
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("enabled")
		                                                                                   .budgetTokens(1024)
		                                                                                   .build())
		                                         .tools(List.of(tool))
		                                         .messages(List.of(
				                                         Message.of("system", "S".repeat(4096)),
				                                         Message.of("user", "Inspect the file")
		                                         ))
		                                         .build();

		JsonNode payload = new JsonMapper().readTree(
				builder.buildOpenAiPayload(request, "arn:aws:codewhisperer:us-east-1:000000000000:profile/fake")
		);
		JsonNode conversation = payload.get("conversationState");
		JsonNode current = conversation.at("/currentMessage/userInputMessage");
		JsonNode specification = current.at(
				"/userInputMessageContext/tools/0/toolSpecification"
		);
		JsonNode schema = specification.at("/inputSchema/json");

		assertEquals("MANUAL", conversation.get("chatTriggerType").asString());
		assertEquals("vibe", payload.get("agentMode").asString());
		assertEquals("vibe", conversation.get("agentTaskType").asString());
		assertFalse(conversation.get("conversationId").asString().isBlank());
		assertFalse(conversation.get("agentContinuationId").asString().isBlank());
		assertFalse(payload.has("systemPrompt"));
		assertTrue(current.get("content").asString().startsWith(
				"<thinking_mode>enabled</thinking_mode>\n" +
				"<max_thinking_length>1024</max_thinking_length>\n"
		));
		assertEquals("arn:aws:codewhisperer:us-east-1:000000000000:profile/fake", payload.get("profileArn").asString());
		assertTrue(current.get("content").asString().endsWith(
				"S".repeat(4096) + "\n\nInspect the file"
		));
		assertEquals("claude-opus-5", current.get("modelId").asString());
		assertEquals("AI_EDITOR", current.get("origin").asString());
		assertTrue(conversation.get("history").isEmpty());
		assertEquals("read_file", specification.get("name").asString());
		assertEquals("Tool: read_file", specification.get("description").asString());
		assertEquals("object", schema.get("type").asString());
		assertTrue(schema.get("properties").isObject());
		assertEquals("path", schema.at("/required/0").asString());
		assertFalse(schema.has("additionalProperties"));
		assertFalse(schema.at("/properties/path").has("additionalProperties"));
	}

	@Test
	void movesOversizedToolDescriptionIntoSystemPrompt() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		String longDescription = "D".repeat(10_001);
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("long-description-session")
		                                         .tools(List.of(tool("workflow", longDescription)))
		                                         .messages(List.of(
				                                         Message.of("system", "Follow instructions"),
				                                         Message.of("user", "Run it")
		                                         ))
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode specification = payload.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools/0/toolSpecification"
		);

		assertFalse(payload.has("systemPrompt"));
		assertEquals(
				"Follow instructions\n\n## Tool: workflow\n\n" + longDescription +
				"\n\nRun it",
				payload.at("/conversationState/currentMessage/userInputMessage/content")
				       .asString()
		);
		assertEquals(
				"[Full documentation in system prompt under '## Tool: workflow']",
				specification.get("description").asString()
		);
		assertFalse(specification.at("/inputSchema/json").has("required"));
		assertFalse(payload.at("/conversationState/history").toString().contains(
				"toolSpecification"
		));
	}

	@Test
	void keepsSystemPromptUntouchedForInlineToolDescriptions() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("inline-description-session")
		                                         .tools(List.of(tool("workflow", "D".repeat(10_000))))
		                                         .messages(List.of(
				                                         Message.of("system", "Follow instructions"),
				                                         Message.of("user", "Run it")
		                                         ))
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode specification = payload.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools/0/toolSpecification"
		);

		assertFalse(payload.has("systemPrompt"));
		assertEquals(
				"Follow instructions\n\nRun it",
				payload.at("/conversationState/currentMessage/userInputMessage/content")
				       .asString()
		);
		assertEquals("D".repeat(10_000), specification.get("description").asString());
	}

	@Test
	void omitsEmptyRequiredFromToolSchemas() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode parameters = new JsonMapper().readTree("""
				{
				  "type": "object",
				  "properties": {
				    "target": {"type": "object", "properties": {}, "required": []}
				  },
				  "required": []
				}
				""");
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("empty-required-session")
		                                         .tools(List.of(Tool.builder()
		                                                            .type("function")
		                                                            .function(Tool.Function.builder()
		                                                                                   .name("cron_list")
		                                                                                   .parameters(parameters)
		                                                                                   .build())
		                                                            .build()))
		                                         .messages(List.of(Message.of("user", "list")))
		                                         .build();

		JsonNode payload = payload(builder, request);
		String tools = payload.at(
				"/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools"
		).toString();

		assertFalse(tools.contains("\"required\":[]"));
		assertFalse(tools.contains("additionalProperties"));
	}

	@Test
	void prefixesSystemPromptToFirstHistoricalUserOnly() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode payload = payload(builder, request(
				"history-session",
				"claude-opus-5",
				"system",
				List.of(
						Message.of("user", "first"),
						Message.of("assistant", "answer"),
						Message.of("user", "current")
				)
		));

		assertEquals(
				"system\n\nfirst",
				payload.at("/conversationState/history/0/userInputMessage/content")
				       .asString()
		);
		assertEquals(
				"current",
				payload.at("/conversationState/currentMessage/userInputMessage/content")
				       .asString()
		);
	}

	/**
	 * The system prompt travels inside the session-start message and nowhere else. Sending it at
	 * the root as well duplicated it — around 100 KB on a one-line request — which pushed payloads
	 * over the size the upstream accepts and came back as "Improperly formed request.".
	 */
	@Test
	void sendsSystemPromptOnlyInsideTheSessionStartMessage() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		String systemPrompt = "é".repeat(100_000);
		JsonNode payload = payload(builder, request(
				"large-system",
				"claude-opus-5",
				systemPrompt,
				List.of(Message.of("user", "Hello"))
		));

		assertFalse(payload.has("systemPrompt"));
		assertEquals(
				systemPrompt + "\n\nHello",
				payload.at("/conversationState/currentMessage/userInputMessage/content")
				       .asString()
		);
	}

	@Test
	void omitsTheRootSystemPromptForSmallPromptsToo() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode payload = payload(builder, request(
				"small-system",
				"claude-opus-5",
				"Be brief.",
				List.of(Message.of("user", "Hello"))
		));

		assertFalse(payload.has("systemPrompt"));
		assertEquals(
				"Be brief.\n\nHello",
				payload.at("/conversationState/currentMessage/userInputMessage/content")
				       .asString()
		);
	}

	@Test
	void replaysExactFirstUserTurnAcrossCompactedFollowUp() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode first = new JsonMapper().readTree(
				builder.buildOpenAiPayload(
						request("session", "claude-opus-5", "system", List.of(
								Message.of("user", "first")
						)),
						null
				)
		);
		JsonNode followUp = new JsonMapper().readTree(
				builder.buildOpenAiPayload(
						request("session", "claude-opus-5", "system", List.of(
								Message.of("assistant", "answer"),
								Message.of("user", "follow-up")
						)),
						null
				)
		);

		assertEquals(
				first.at("/conversationState/conversationId").asString(),
				followUp.at("/conversationState/conversationId").asString()
		);
		assertEquals(
				first.at("/conversationState/agentContinuationId").asString(),
				followUp.at("/conversationState/agentContinuationId").asString()
		);
		assertEquals(
				"system\n\nfirst",
				followUp.at("/conversationState/history/0/userInputMessage/content").asString()
		);
		assertEquals(
				"answer",
				followUp.at("/conversationState/history/1/assistantResponseMessage/content").asString()
		);
		assertEquals(
				"follow-up",
				followUp.at("/conversationState/currentMessage/userInputMessage/content").asString()
		);
	}

	@Test
	void resetsSessionWhenSystemOrModelChanges() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		JsonNode original = payload(builder, request(
				"session", "claude-opus-5", "system-a", List.of(Message.of("user", "first"))
		));
		JsonNode changedSystem = payload(builder, request(
				"session", "claude-opus-5", "system-b", List.of(Message.of("user", "first"))
		));
		JsonNode changedModel = payload(builder, request(
				"session", "claude-sonnet-5", "system-a", List.of(Message.of("user", "first"))
		));

		assertNotEquals(
				original.at("/conversationState/conversationId").asString(),
				changedSystem.at("/conversationState/conversationId").asString()
		);
		assertNotEquals(
				original.at("/conversationState/conversationId").asString(),
				changedModel.at("/conversationState/conversationId").asString()
		);
	}

	@Test
	void anonymousRequestsNeverShareSessionState() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = request(
				null, "claude-opus-5", "system", List.of(Message.of("user", "first"))
		);

		JsonNode first = payload(builder, request);
		JsonNode second = payload(builder, request);

		assertNotEquals(
				first.at("/conversationState/conversationId").asString(),
				second.at("/conversationState/conversationId").asString()
		);
	}

	@Test
	void matchesToolResultsByIdAndSalvagesDuplicateAndOrphanResults() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		Tool tool = tool("read_file");
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("tool-session")
		                                         .tools(List.of(tool))
		                                         .messages(List.of(
				                                         Message.of("user", "read"),
				                                         assistantToolCall("call-1", "read_file"),
				                                         toolResult("call-1", "ok", false),
				                                         toolResult("call-1", "duplicate", false),
				                                         toolResult("missing", "orphan", true)
		                                         ))
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode history = payload.at("/conversationState/history");
		JsonNode current = payload.at("/conversationState/currentMessage/userInputMessage");

		assertEquals(
				"call-1",
				history.at("/1/assistantResponseMessage/toolUses/0/toolUseId").asString(),
				payload.toPrettyString()
		);
		assertEquals("call-1", current.at("/userInputMessageContext/toolResults/0/toolUseId").asString());
		assertEquals("success", current.at("/userInputMessageContext/toolResults/0/status").asString());
		assertTrue(current.get("content").asString().contains("[Tool result: duplicate]"));
		assertTrue(current.get("content").asString().contains("[Tool result: orphan]"));
		assertEquals(1, current.at("/userInputMessageContext/tools").size());
		assertEquals(1, current.at("/userInputMessageContext/toolResults").size());
	}

	@Test
	void flattensToolInteractionsWhenToolsAreOmitted() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("no-tools-session")
		                                         .messages(List.of(
				                                         Message.of("user", "read"),
				                                         assistantToolCall("call-1", "read_file"),
				                                         toolResult("call-1", "contents", false)
		                                         ))
		                                         .build();

		JsonNode payload = payload(builder, request);
		String serialized = payload.toString();

		assertFalse(serialized.contains("toolUses"));
		assertFalse(serialized.contains("toolResults"));
		assertTrue(serialized.contains("[Tool call: read_file {}]"));
		assertTrue(serialized.contains("[Tool result: contents]"));
	}

	@Test
	void mergesConsecutiveRolesAndKeepsCurrentUser() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = request(
				"merged-session",
				"claude-opus-5",
				null,
				List.of(
						Message.of("user", "one"),
						Message.of("user", "two"),
						Message.of("assistant", "three"),
						Message.of("assistant", "four"),
						Message.of("user", "five")
				)
		);

		JsonNode payload = payload(builder, request);
		JsonNode history = payload.at("/conversationState/history");

		assertEquals(2, history.size());
		assertEquals("one\n\ntwo", history.at("/0/userInputMessage/content").asString());
		assertEquals("three\n\nfour", history.at("/1/assistantResponseMessage/content").asString());
		assertEquals("five", payload.at("/conversationState/currentMessage/userInputMessage/content").asString());
	}

	@Test
	void usesMinimalCurrentMessageWhenConversationEndsWithAssistant() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = request(
				"assistant-session",
				"claude-opus-5",
				null,
				List.of(Message.of("user", "question"), Message.of("assistant", "answer"))
		);

		JsonNode payload = payload(builder, request);

		assertEquals("[continue]", payload.at("/conversationState/currentMessage/userInputMessage/content").asString());
		assertEquals("claude-opus-5", payload.at("/conversationState/currentMessage/userInputMessage/modelId").asString());
	}

	/**
	 * A tool-result turn is the current message on every iteration of a tool loop, and its
	 * filler content reaches the model as user speech. Filler that looks like an empty or
	 * near-empty message makes the model answer the filler instead of the tool results.
	 */
	@Test
	void labelsToolResultTurnInsteadOfSendingBareFiller() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("filler-session")
		                                         .tools(List.of(tool("read_file")))
		                                         .messages(List.of(
				                                         Message.of("user", "read"),
				                                         assistantToolCall("call-1", "read_file"),
				                                         toolResult("call-1", "contents", false)
		                                         ))
		                                         .build();

		JsonNode current = payload(builder, request)
				.at("/conversationState/currentMessage/userInputMessage");
		String content = current.get("content").asString();

		assertEquals("[tool results]", content);
		assertEquals(
				"call-1",
				current.at("/userInputMessageContext/toolResults/0/toolUseId").asString()
		);
	}

	@Test
	void truncatesWholeOldestTurns() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		String large = "x".repeat(310_000);
		InternalRequest request = request(
				"truncate-session",
				"claude-opus-5",
				null,
				List.of(
						Message.of("user", "old-user-" + large),
						Message.of("assistant", "old-assistant-" + large),
						Message.of("user", "keep-user-" + large),
						Message.of("assistant", "keep-assistant"),
						Message.of("user", "current")
				)
		);

		JsonNode payload = payload(builder, request);
		JsonNode history = payload.at("/conversationState/history");

		assertEquals(2, history.size());
		assertTrue(history.at("/0/userInputMessage/content").asString().startsWith("keep-user-"));
		assertEquals("keep-assistant", history.at("/1/assistantResponseMessage/content").asString());
		assertEquals("current", payload.at("/conversationState/currentMessage/userInputMessage/content").asString());
	}

	@Test
	void truncationPreservesFrozenSessionStart() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		payload(builder, request(
				"replay-truncation",
				"claude-opus-5",
				null,
				List.of(Message.of("user", "first"))
		));
		String large = "x".repeat(310_000);
		JsonNode payload = payload(builder, request(
				"replay-truncation",
				"claude-opus-5",
				null,
				List.of(
						Message.of("user", "compacted"),
						Message.of("assistant", "old-1-" + large),
						Message.of("user", "old-2-" + large),
						Message.of("assistant", "old-3-" + large),
						Message.of("user", "current")
				)
		));

		assertEquals(
				"first",
				payload.at("/conversationState/history/0/userInputMessage/content").asString()
		);
		assertEquals(
				"current",
				payload.at("/conversationState/currentMessage/userInputMessage/content").asString()
		);
	}

	@Test
	void truncationDropsToolUseWithItsResultCarrier() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		String large = "x".repeat(460_000);
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .clientSessionId("tool-truncation")
		                                         .tools(List.of(tool("read_file")))
		                                         .messages(List.of(
				                                         Message.of("user", "old-" + large),
				                                         assistantToolCall("call-1", "read_file"),
				                                         toolResult("call-1", "result", false),
				                                         Message.of("assistant", "answer-" + large),
				                                         Message.of("user", "current")
		                                         ))
		                                         .build();

		JsonNode payload = payload(builder, request);
		String history = payload.at("/conversationState/history").toString();

		assertFalse(history.contains("call-1"));
		assertEquals(
				"current",
				payload.at("/conversationState/currentMessage/userInputMessage/content").asString()
		);
	}

	@Test
	void emitsExplicitInferenceConfig() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .messages(List.of(Message.of("user", "Hello")))
		                                         .temperature(0.25)
		                                         .topP(0.8)
		                                         .maxTokens(4096)
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode inference = payload.get("inferenceConfig");

		assertEquals(4096, inference.get("maxTokens").asInt());
		assertEquals(0.25, inference.get("temperature").asDouble());
		assertEquals(0.8, inference.get("topP").asDouble());
	}

	@Test
	void omitsSamplingFieldsWhenThinkingIsEnabled() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .messages(List.of(Message.of("user", "Hello")))
		                                         .temperature(0.7)
		                                         .topP(0.9)
		                                         .maxTokens(4096)
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("adaptive")
		                                                                                   .budgetTokens(2048)
		                                                                                   .build())
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode inference = payload.get("inferenceConfig");

		assertEquals(4096, inference.get("maxTokens").asInt());
		assertFalse(inference.has("temperature"));
		assertFalse(inference.has("topP"));
	}

	@Test
	void emitsNativeClaudeEffortWithThinkingPrefix() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .messages(List.of(Message.of("user", "Hello")))
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("enabled")
		                                                                                   .budgetTokens(2048)
		                                                                                   .build())
		                                         .effort("max")
		                                         .build();

		JsonNode payload = payload(builder, request);
		JsonNode additional = payload.get("additionalModelRequestFields");

		assertEquals("adaptive", additional.at("/thinking/type").asString());
		assertEquals("summarized", additional.at("/thinking/display").asString());
		assertEquals("high", additional.at("/output_config/effort").asString());
		assertTrue(payload.at(
				"/conversationState/currentMessage/userInputMessage/content"
		).asString().contains(
				"<thinking_mode>enabled</thinking_mode>\n" +
				"<max_thinking_length>2048</max_thinking_length>"
		));
	}

	@Test
	void emitsNativeGptEffortSchema() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("gpt-5.6")
		                                         .messages(List.of(Message.of("user", "Hello")))
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("enabled")
		                                                                                   .budgetTokens(2048)
		                                                                                   .build())
		                                         .effort("max")
		                                         .build();

		JsonNode payload = payload(builder, request);

		assertEquals(
				"xhigh",
				payload.at("/additionalModelRequestFields/reasoning/effort").asString()
		);
		assertTrue(payload.at(
				"/additionalModelRequestFields/output_config"
		).isMissingNode());
		assertFalse(payload.at(
				"/conversationState/currentMessage/userInputMessage/content"
		).asString().contains("<thinking_mode>"));
	}

	@Test
	void appliesBoundedThinkingPrefixOnceForUnsupportedModel() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-sonnet-4.5")
		                                         .messages(List.of(
				                                         Message.of(
						                                         "system",
						                                         "<thinking_mode>enabled</thinking_mode>\nExisting"
				                                         ),
				                                         Message.of("user", "Hello")
		                                         ))
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("enabled")
		                                                                                   .budgetTokens(99_999)
		                                                                                   .build())
		                                         .build();

		JsonNode payload = payload(builder, request);
		String content = payload.at(
				"/conversationState/currentMessage/userInputMessage/content"
		).asString();

		assertEquals(1, content.split("<thinking_mode>", -1).length - 1);
		assertFalse(payload.has("additionalModelRequestFields"));
	}

	@Test
	void omitsDisabledAndInvalidInferenceValues() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-opus-5")
		                                         .messages(List.of(Message.of("user", "Hello")))
		                                         .maxTokens(0)
		                                         .thinking(InternalRequest.Thinking.builder()
		                                                                                   .type("disabled")
		                                                                                   .budgetTokens(2048)
		                                                                                   .build())
		                                         .effort("invalid")
		                                         .build();

		JsonNode payload = payload(builder, request);

		assertFalse(payload.has("inferenceConfig"));
		assertFalse(payload.has("additionalModelRequestFields"));
		assertFalse(payload.has("systemPrompt"));
	}

	@Test
	void omitsProfileForApiKeyAccount() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		InternalRequest request = requestWithUserContent("Hello");

		JsonNode payload = new JsonMapper().readTree(
				builder.buildOpenAiPayload(request, null)
		);

		assertFalse(payload.has("profileArn"));
	}

	@Test
	void enforcesPayloadLimitUsingUtf8Bytes() throws Exception {
		PayloadBuilder builder = new PayloadBuilder(new ModelResolver());
		// "é" is two UTF-8 bytes, so the character counts here are half the byte budget.
		String underLimitPayload = builder.buildOpenAiPayload(
				requestWithUserContent("é".repeat(299_000)),
				null
		);

		assertTrue(underLimitPayload.getBytes(StandardCharsets.UTF_8).length < 600_000);
		assertThrows(
				IllegalArgumentException.class,
				() -> builder.buildOpenAiPayload(
						requestWithUserContent("é".repeat(301_000)),
						null
				)
		);
	}

	private static Tool tool(String name) throws Exception {
		return tool(name, "Tool: " + name);
	}

	private static Tool tool(String name, String description) throws Exception {
		JsonNode parameters = new JsonMapper().readTree(
				"{\"type\":\"object\",\"properties\":{}}"
		);
		return Tool.builder()
		           .type("function")
		           .function(Tool.Function.builder()
		                                  .name(name)
		                                  .description(description)
		                                  .parameters(parameters)
		                                  .build())
		           .build();
	}

	private static Message assistantToolCall(String id, String name) {
		return Message.builder()
		              .role("assistant")
		              .content("")
		              .toolCalls(List.of(Message.ToolCall.builder()
		                                                    .id(id)
		                                                    .type("function")
		                                                    .function(Message.Function.builder()
		                                                                              .name(name)
		                                                                              .arguments("{}")
		                                                                              .build())
		                                                    .build()))
		              .build();
	}

	private static Message toolResult(String id, String content, boolean error) {
		return Message.builder()
		              .role("tool")
		              .toolCallId(id)
		              .content(content)
		              .toolError(error)
		              .build();
	}

	private static JsonNode payload(
			PayloadBuilder builder,
			InternalRequest request
	) throws Exception {
		return new JsonMapper().readTree(builder.buildOpenAiPayload(request, null));
	}

	private static InternalRequest request(
			String sessionId,
			String model,
			String system,
			List<Message> messages
	) {
		List<Message> requestMessages = new ArrayList<>();
		if (system != null) requestMessages.add(Message.of("system", system));
		requestMessages.addAll(messages);
		return InternalRequest.builder()
		                      .model(model)
		                      .clientSessionId(sessionId)
		                      .messages(requestMessages)
		                      .build();
	}

	private static InternalRequest requestWithUserContent(String content) {
		return request(
				"payload-size-session-" + content.length(),
				"claude-opus-5",
				null,
				List.of(Message.of("user", content))
		);
	}
}
