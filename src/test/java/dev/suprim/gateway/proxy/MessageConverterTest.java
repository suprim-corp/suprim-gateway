package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.MessagesRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageConverterTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	/**
	 * A user turn may hold text of its own next to its tool results. Dropping that text
	 * silently loses whatever the user typed while the tool call was in flight.
	 */
	@Test
	void keepsUserTextThatAccompaniesToolResults() {
		JsonNode content = MAPPER.readTree("""
				[
				  {"type": "tool_result", "tool_use_id": "call-1", "content": "file contents"},
				  {"type": "text", "text": "actually check the other file too"}
				]
				""");

		List<Message> messages = MessageConverter.fromAnthropic(request(content));

		assertEquals(2, messages.size(), messages.toString());
		assertEquals("tool", messages.get(0).role());
		assertEquals("call-1", messages.get(0).toolCallId());
		assertEquals("file contents", messages.get(0).content());
		assertEquals("user", messages.get(1).role());
		assertEquals("actually check the other file too", messages.get(1).content());
	}

	@Test
	void emitsOnlyToolMessagesWhenTurnHasNoText() {
		JsonNode content = MAPPER.readTree("""
				[{"type": "tool_result", "tool_use_id": "call-1", "content": "ok"}]
				""");

		List<Message> messages = MessageConverter.fromAnthropic(request(content));

		assertEquals(1, messages.size(), messages.toString());
		assertEquals("tool", messages.getFirst().role());
	}

	private static MessagesRequest request(JsonNode userContent) {
		return new MessagesRequest(
				"claude-opus-5",
				1024,
				List.of(new MessagesRequest.Message("user", userContent)),
				null, null, null, null, null, null, null, null, null, null
		);
	}
}
