package dev.suprim.gateway.provider.kiro.payload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiroSessionReplayTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@AfterEach
	void clearSessions() {
		KiroSessionReplay.clear();
	}

	@Test
	void freezesFirstTurnAndReturnsDefensiveCopies() {
		ObjectNode firstTurn = userMessage("first");
		KiroSessionReplay.ReplayState created = KiroSessionReplay.resolve(
				"session", "model", "system", firstTurn
		);
		firstTurn.put("content", "mutated input");
		created.session().frozenSessionStart().put("content", "mutated output");

		KiroSessionReplay.ReplayState replayed = KiroSessionReplay.resolve(
				"session", "model", "system", userMessage("follow-up")
		);

		assertTrue(created.created());
		assertFalse(replayed.created());
		assertEquals(created.session().ids(), replayed.session().ids());
		assertEquals("first", replayed.session().frozenSessionStart().get("content").asString());
	}

	@Test
	void isolatesModelSystemAndAnonymousRequests() {
		KiroSessionReplay.ReplayState original = KiroSessionReplay.resolve(
				" session ", "model-a", "system-a", userMessage("first")
		);
		KiroSessionReplay.ReplayState same = KiroSessionReplay.resolve(
				"session", "model-a", "system-a", userMessage("next")
		);
		KiroSessionReplay.ReplayState differentModel = KiroSessionReplay.resolve(
				"session", "model-b", "system-a", userMessage("first")
		);
		KiroSessionReplay.ReplayState differentSystem = KiroSessionReplay.resolve(
				"session", "model-a", "system-b", userMessage("first")
		);
		KiroSessionReplay.ReplayState anonymousOne = KiroSessionReplay.resolve(
				null, "model-a", "system-a", userMessage("first")
		);
		KiroSessionReplay.ReplayState anonymousTwo = KiroSessionReplay.resolve(
				" ", "model-a", "system-a", userMessage("first")
		);

		assertEquals(original.session().ids(), same.session().ids());
		assertNotEquals(original.session().ids(), differentModel.session().ids());
		assertNotEquals(original.session().ids(), differentSystem.session().ids());
		assertNotEquals(anonymousOne.session().ids(), anonymousTwo.session().ids());
		assertTrue(differentModel.created());
		assertTrue(differentSystem.created());
		assertTrue(anonymousOne.created());
		assertTrue(anonymousTwo.created());
	}

	@Test
	void retainsConfiguredCacheBounds() {
		assertEquals(5_000, KiroSessionReplay.maximumSize());
		assertEquals(Duration.ofHours(2), KiroSessionReplay.sessionTtl());
	}

	private static ObjectNode userMessage(String content) {
		ObjectNode message = MAPPER.createObjectNode();
		message.put("content", content);
		message.put("modelId", "model");
		message.put("origin", "AI_EDITOR");
		return message;
	}
}
