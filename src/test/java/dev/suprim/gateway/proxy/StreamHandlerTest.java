package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamHandlerTest {

	@Test
	void measuresFirstReasoningEventBeforeVisibleContent() throws Exception {
		StringWriter output = new StringWriter();
		StreamingEventWriter eventWriter = new StreamingEventWriter(
				new PrintWriter(output),
				new StreamConverter(),
				Format.COMPLETION,
				"gpt-5.6-terra"
		);
		KiroResponse response = new KiroResponse(
				200,
				new ByteArrayInputStream(
						"{\"reasoningContentEvent\":{\"text\":\"thinking\"}}"
								.getBytes()
				),
				"text/event-stream"
			);

		StreamHandler.StreamResult result = new StreamHandler(
				new TokenEstimator()
		).streamToWriter(
				response,
				new PrintWriter(output),
				eventWriter,
				System.currentTimeMillis() - 50
		);

		assertTrue(result.firstTokenMs() >= 50);
	}
}
