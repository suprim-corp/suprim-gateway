package dev.suprim.gateway.logging;

import dev.suprim.gateway.proxy.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestLogCallTest {

	private final RequestLogCall call = RequestLogCall.builder()
	                                                     .model("model")
	                                                     .streaming(true)
	                                                     .estimatedInputTokens(11)
	                                                     .virtualKeyId("key")
	                                                     .clientIp("127.0.0.1")
	                                                     .format(Format.COMPLETION)
	                                                     .startedAt(System.currentTimeMillis())
	                                                     .build();

	@Test
	void success_prefersReportedUsageAndKeepsCredits() {
		RequestLogEvent event = call.success("account", 7, 5, 3L, 1.5).event();

		assertEquals(7, event.promptTokens());
		assertEquals(5, event.completionTokens());
		assertEquals(3, event.firstTokenMs());
		assertEquals(1.5, event.credits());
	}

	@Test
	void success_fallsBackToEstimatedInputAndLeavesUnknownOutputEmpty() {
		RequestLogEvent event = call.success("account", null, 0, null, 0.0).event();

		assertEquals(11, event.promptTokens());
		assertNull(event.completionTokens());
		assertNull(event.credits());
	}

	@Test
	void upstreamError_truncatesBodyAndUsesEstimatedInput() {
		RequestLogEvent event = call.upstreamError("account", 502, "x".repeat(250)).event();

		assertEquals(502, event.status());
		assertEquals(11, event.promptTokens());
		assertEquals(200, event.errorMessage().length());
		assertNull(event.completionTokens());
	}
}
