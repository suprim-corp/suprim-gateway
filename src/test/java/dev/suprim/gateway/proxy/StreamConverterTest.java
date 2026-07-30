package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.sse.AnthropicSsePayloads;
import dev.suprim.gateway.proxy.sse.CompletionsSsePayloads.CompletionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamConverterTest {

	@Test
	void anthropicNonStreamingIncludesCacheUsage() {
		AnthropicSsePayloads.AnthropicResponse response =
				new StreamConverter().toAnthropicNonStreaming(
						"msg_1",
						"claude-opus-5",
						"hello",
						null,
						StreamHandler.Usage.builder()
						                   .promptTokens(17)
						                   .outputTokens(42)
						                   .cacheReadTokens(5)
						                   .cacheCreationTokens(7)
						                   .credits(0)
						                   .build()
				);

		assertEquals(17, response.usage().inputTokens());
		assertEquals(42, response.usage().outputTokens());
		assertEquals(5, response.usage().cacheReadInputTokens());
		assertEquals(7, response.usage().cacheCreationInputTokens());
	}

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
