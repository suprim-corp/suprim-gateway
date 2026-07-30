package dev.suprim.gateway.proxy.kiro;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KiroEventDispatcherTest {

	private static final JsonMapper MAPPER = new JsonMapper();

	@Test
	void parsesWrappedAndBareUsageSnapshots() throws Exception {
		KiroEventDispatcher dispatcher = new KiroEventDispatcher();
		KiroEvent wrapped = only(dispatcher.dispatch(json("""
				{
				  "metricsEvent": {
				    "inputTokens": 10,
				    "outputTokens": 4,
				    "cacheReadInputTokens": 3,
				    "cache_creation_input_tokens": 2
				  }
				}
				""")));
		KiroEvent bare = only(dispatcher.dispatch(json("""
				{
				  "__eventType": "metricsEvent",
				  "inputTokens": 12,
				  "outputTokens": 5
				}
				""")));

		assertEquals(KiroEvent.usage(10, 4, 3, 2, null), wrapped);
		assertEquals(KiroEvent.usage(12, 5, null, null, null), bare);
	}

	@Test
	void parsesContextAndMeteringWithoutInventingZeros() throws Exception {
		KiroEventDispatcher dispatcher = new KiroEventDispatcher();
		KiroEvent context = only(dispatcher.dispatch(json("""
				{"contextUsageEvent":{"contextUsagePercentage":42.5}}
				""")));
		KiroEvent zeroCredits = only(dispatcher.dispatch(json("""
				{"meteringEvent":{"usage":0}}
				""")));

		assertEquals(KiroEvent.usage(null, null, null, null, 42.5), context);
		assertEquals(0.0, zeroCredits.credits());
		assertNull(zeroCredits.usage());
	}

	@Test
	void rejectsInvalidUsageValues() throws Exception {
		KiroEventDispatcher dispatcher = new KiroEventDispatcher();
		List<KiroEvent> events = dispatcher.dispatch(json("""
				{
				  "metricsEvent": {
				    "inputTokens": -1,
				    "outputTokens": "NaN",
				    "cacheReadInputTokens": 0
				  }
				}
				"""));

		assertEquals(
				KiroEvent.usage(null, null, 0, null, null),
				only(events)
		);
	}

	private static KiroEvent only(List<KiroEvent> events) {
		assertEquals(1, events.size());
		return events.getFirst();
	}

	private static JsonNode json(String value) throws Exception {
		return MAPPER.readTree(value);
	}
}
