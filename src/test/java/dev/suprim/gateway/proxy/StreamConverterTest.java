package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamConverterTest {

	@Test
	void openAiNonStreamingIncludesUsage() {
		CompletionResponse response = new StreamConverter().toOpenAiNonStreaming(
				List.of(KiroEvent.content("hello")),
				"gpt-5.6-terra",
				null,
				17,
				42
		);

		assertEquals(17, response.usage().promptTokens());
		assertEquals(42, response.usage().completionTokens());
		assertEquals(59, response.usage().totalTokens());
	}
}
