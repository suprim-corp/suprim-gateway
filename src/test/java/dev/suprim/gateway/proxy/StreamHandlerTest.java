package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamHandlerTest {

	@Test
	void aggregatesLatestUsageSnapshotAndSumsCredits() {
		StreamHandler.UsageAccumulator usage = new StreamHandler.UsageAccumulator();

		usage.accept(KiroEvent.usage(10, 4, 3, null, 25.0));
		usage.accept(KiroEvent.metering(1.25));
		usage.accept(KiroEvent.usage(12, 6, 0, 2, 40.0));
		usage.accept(KiroEvent.metering(0.75));

		assertEquals(
				StreamHandler.Usage.builder()
				                   .promptTokens(12)
				                   .outputTokens(6)
				                   .cacheReadTokens(0)
				                   .cacheCreationTokens(2)
				                   .contextPercentage(40.0)
				                   .credits(2.0)
				                   .build(),
				usage.result()
		);
	}

	@Test
	void keepsAbsentMetricsDistinctFromReportedZero() {
		StreamHandler.UsageAccumulator usage = new StreamHandler.UsageAccumulator();

		usage.accept(KiroEvent.usage(null, 0, null, null, null));

		assertEquals(
				StreamHandler.Usage.builder()
				                   .outputTokens(0)
				                   .credits(0.0)
				                   .build(),
				usage.result()
		);
	}

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
