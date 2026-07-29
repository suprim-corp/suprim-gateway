package dev.suprim.gateway.proxy.token;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestOptimizerTest {

	private final RequestOptimizer optimizer = new RequestOptimizer();

	@Test
	void bypassesOutOfScopeProviders() {
		InternalRequest request = request(longText("line"), false, List.of());

		assertSame(request, optimizer.optimize(Provider.XAI, request).request());
		assertSame(request, optimizer.optimize(Provider.DEEPSEEK, request).request());
	}

	@Test
	void preservesTypedErrorsAndOrdinaryRepeatedLines() {
		String repeated = ("same semantic line\n").repeat(100);
		InternalRequest error = request(repeated, true, List.of());
		InternalRequest success = request(repeated, false, List.of());

		assertEquals(repeated, toolContent(optimizer.optimize(Provider.KIRO, error).request()));
		assertEquals(repeated, toolContent(optimizer.optimize(Provider.KIRO, success).request()));
	}

	@Test
	void collapsesOnlyConsecutiveTimestampedLogs() {
		String logs = ("2026-07-29T10:00:00Z INFO ready\n").repeat(100);

		String optimized = toolContent(optimizer.optimize(
				Provider.CODEX, request(logs, false, List.of())
		).request());

		assertTrue(optimized.contains("previous log repeated 99 times"));
		assertNotEquals(logs, optimized);
	}

	@Test
	void appliesExplicitWebToolEquivalenceRulesIdempotently() {
		Tool webSearch = tool("WebSearch");
		Tool exa = tool("mcp__exa__web_search_exa");
		InternalRequest request = request("short", false, List.of(webSearch, exa));

		InternalRequest once = optimizer.optimize(Provider.ANTIGRAVITY, request).request();
		InternalRequest twice = optimizer.optimize(Provider.ANTIGRAVITY, once).request();

		assertEquals(List.of(exa), once.tools());
		assertEquals(once.tools(), twice.tools());
	}

	private static InternalRequest request(String content, boolean error, List<Tool> tools) {
		return InternalRequest.builder()
		                      .model("model")
		                      .messages(List.of(Message.builder()
		                                               .role("tool")
		                                               .content(content)
		                                               .toolCallId("call-1")
		                                               .toolError(error)
		                                               .build()))
		                      .tools(tools)
		                      .build();
	}

	private static Tool tool(String name) {
		return Tool.builder()
		           .type("function")
		           .function(Tool.Function.builder().name(name).build())
		           .build();
	}

	private static String toolContent(InternalRequest request) {
		return (String) request.messages().getFirst().content();
	}

	private static String longText(String line) {
		return (line + "\n").repeat(200);
	}
}
