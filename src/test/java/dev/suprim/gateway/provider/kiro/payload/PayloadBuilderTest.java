package dev.suprim.gateway.provider.kiro.payload;

import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadBuilderTest {

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

		assertEquals("Follow instructions", payload.get("systemPrompt").asString());
		assertEquals("vibe", payload.get("agentMode").asString());
		assertEquals("vibe", conversation.get("agentTaskType").asString());
		assertTrue(!conversation.get("conversationId").asString().isBlank());
		assertTrue(!conversation.get("agentContinuationId").asString().isBlank());
		assertTrue(conversation.get("history") == null);
		assertEquals(
				"Follow instructions\n\nHello",
				conversation.at("/currentMessage/userInputMessage/content").asString()
		);
	}
}
