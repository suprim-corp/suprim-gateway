package dev.suprim.gateway.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ActionLoggerTest {

	private ListAppender<ILoggingEvent> captureLog() {
		Logger logger = (Logger) LoggerFactory.getLogger(ActionLogger.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private String lastMessage(ListAppender<ILoggingEvent> appender) {
		return appender.list.getLast().getFormattedMessage();
	}

	@Test
	void logRequest_basicGetNoBody() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("GET", "/v1/models", "127.0.0.1", null, null);
		String msg = lastMessage(appender);
		assertThat(msg).contains("→ GET /v1/models");
		assertThat(msg).contains("127.0.0.1");
		assertThat(msg).doesNotContain("body:");
	}

	@Test
	void logRequest_withQueryString() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest(
				"GET",
				"/v1/models",
				"10.0.0.1",
				"page=1",
				null
		);
		String msg = lastMessage(appender);
		assertThat(msg).contains("?page=1");
	}

	@Test
	void logRequest_withBody() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest(
				"POST",
				"/v1/chat/completions",
				"10.0.0.1",
				null,
				"{\"model\":\"claude\"}"
		);
		String msg = lastMessage(appender);
		assertThat(msg).contains("body:");
		assertThat(msg).contains("\"model\":\"claude\"");
	}

	@Test
	void logRequest_masksSensitiveFields() {
		ListAppender<ILoggingEvent> appender = captureLog();
		String body = "{\"password\":\"hunter2\",\"token\":\"abc123\",\"secret\":\"xyz\",\"api_key\":\"key1\",\"apikey\":\"key2\",\"authorization\":\"Bearer x\",\"refresh_token\":\"rt\",\"access_token\":\"at\",\"credentials\":\"cred\"}";
		ActionLogger.logRequest("POST", "/auth", "10.0.0.1", null, body);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("hunter2");
		assertThat(msg).doesNotContain("abc123");
		assertThat(msg).doesNotContain("xyz");
		assertThat(msg).doesNotContain("key1");
		assertThat(msg).doesNotContain("key2");
		assertThat(msg).contains("***");
	}

	@Test
	void logRequest_truncatesLongBody() {
		ListAppender<ILoggingEvent> appender = captureLog();
		String longBody = "x".repeat(600);
		ActionLogger.logRequest("POST", "/api", "10.0.0.1", null, longBody);
		String msg = lastMessage(appender);
		assertThat(msg).contains("...[truncated]");
		assertThat(msg).doesNotContain("x".repeat(600));
	}

	@Test
	void logRequest_blankBodyNotLogged() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("POST", "/api", "10.0.0.1", null, "   ");
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("body:");
	}

	@Test
	void logRequest_emptyQueryNotShown() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("GET", "/api", "10.0.0.1", "", null);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("?");
	}

	@Test
	void logResponse_2xxGreenColorAndDuration() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse(
				"GET",
				"/v1/models",
				200,
				"{\"data\":[]}",
				42,
				null
		);
		String msg = lastMessage(appender);
		assertThat(msg).contains("← 200");
		assertThat(msg).contains("(42ms)");
		assertThat(msg).contains("[32m");
	}

	@Test
	void logResponse_4xxYellowColor() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse(
				"POST",
				"/v1/chat/completions",
				401,
				null,
				10,
				null
		);
		String msg = lastMessage(appender);
		assertThat(msg).contains("[33m");
		assertThat(msg).contains("← 401");
	}

	@Test
	void logResponse_5xxRedColor() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse("POST", "/api", 500, null, 100, null);
		String msg = lastMessage(appender);
		assertThat(msg).contains("[31m");
	}

	@Test
	void logResponse_withAuthInfo() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse("GET", "/api", 200, null, 5, "key-abc");
		String msg = lastMessage(appender);
		assertThat(msg).contains("[key-abc]");
	}

	@Test
	void logResponse_nullAuthInfoNotShown() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse("GET", "/api", 200, null, 5, null);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("[null]");
	}

	@Test
	void logResponse_emptyAuthInfoNotShown() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse("GET", "/api", 200, null, 5, "");
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("[]");
	}

	@Test
	void logResponse_blankBodyNotLogged() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse("GET", "/api", 200, "  ", 5, null);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("response:");
	}

	@Test
	void summarizeJson_object() {
		String result = ActionLogger.summarizeJson(
				"{\"model\":\"claude\",\"messages\":[],\"stream\":true}");
		assertThat(result).isEqualTo("{model, messages, stream}");
	}

	@Test
	void summarizeJson_array() {
		String result = ActionLogger.summarizeJson(
				"[{\"id\":1},{\"id\":2},{\"id\":3}]");
		assertThat(result).isEqualTo("List[3 items]");
	}

	@Test
	void summarizeJson_emptyObject() {
		String result = ActionLogger.summarizeJson("{}");
		assertThat(result).isEqualTo("{}");
	}

	@Test
	void summarizeJson_emptyArray() {
		String result = ActionLogger.summarizeJson("[]");
		assertThat(result).isEqualTo("List[0 items]");
	}

	@Test
	void summarizeJson_nestedObjectKeysOnly() {
		String result = ActionLogger.summarizeJson(
				"{\"a\":{\"b\":1},\"c\":[1,2]}");
		assertThat(result).isEqualTo("{a, c}");
	}

	@Test
	void summarizeJson_invalidJsonFallsBackToTruncate() {
		String result = ActionLogger.summarizeJson("not json at all");
		assertThat(result).isEqualTo("not json at all");
	}

	@Test
	void summarizeJson_longInvalidJsonGetsTruncated() {
		String longText = "x".repeat(600);
		String result = ActionLogger.summarizeJson(longText);
		assertThat(result).contains("...[truncated]");
		assertThat(result).hasSize(500 + "...[truncated]".length());
	}

	@Test
	void summarizeJson_nullReturnsEmpty() {
		assertThat(ActionLogger.summarizeJson(null)).isEqualTo("");
	}

	@Test
	void summarizeJson_blankReturnsEmpty() {
		assertThat(ActionLogger.summarizeJson("   ")).isEqualTo("");
	}

	@Test
	void summarizeJson_arrayWithPrimitives() {
		String result = ActionLogger.summarizeJson("[1,2,3,\"hello\",true]");
		assertThat(result).isEqualTo("List[5 items]");
	}

	@Test
	void logResponse_jsonObjectSummarized() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logResponse(
				"POST",
				"/api",
				200,
				"{\"id\":\"123\",\"choices\":[]}",
				50,
				null
		);
		String msg = lastMessage(appender);
		assertThat(msg).contains("response: {id, choices}");
	}

	@Test
	void logRequest_maskingCaseInsensitive() {
		ListAppender<ILoggingEvent> appender = captureLog();
		String body = "{\"Password\":\"secret123\",\"TOKEN\":\"val\"}";
		ActionLogger.logRequest("POST", "/auth", "10.0.0.1", null, body);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("secret123");
		assertThat(msg).doesNotContain("val");
		assertThat(msg).contains("***");
	}

	@Test
	void summarizeJson_topLevelArrayDirectly() {
		String result = ActionLogger.summarizeJson("[\"a\",\"b\"]");
		assertThat(result).isEqualTo("List[2 items]");
	}

	@Test
	void summarizeJson_nestedArraysAtDepth1() {
		String result = ActionLogger.summarizeJson("[[1,2],[3,4],[5]]");
		assertThat(result).isEqualTo("List[3 items]");
	}

	@Test
	void summarizeJson_truncatedJsonFallsBack() {
		String result = ActionLogger.summarizeJson("{\"a\":1,\"b\":");
		// either returns partial keys or falls back to truncate — both are valid
		assertThat(result).isIn("{a, b}", "{\"a\":1,\"b\":");
	}

	@Test
	void summarizeJson_truncatedObject_openBraceOnly() {
		String result = ActionLogger.summarizeJson("{");
		assertThat(result).isIn("{}", "{");
	}

	@Test
	void summarizeJson_truncatedArray_openBracketOnly() {
		String result = ActionLogger.summarizeJson("[");
		assertThat(result).isIn("List[0 items]", "[");
	}

	@Test
	void summarizeJson_startsWithNumber() {
		String result = ActionLogger.summarizeJson("42");
		assertThat(result).isEqualTo("42");
	}

	@Test
	void summarizeJson_deeplyNestedArrayNoDepth1Element() {
		// outer array contains one nested array — the nested array's elements are at depth > 1
		String result = ActionLogger.summarizeJson("[[[]]]");
		assertThat(result).isEqualTo("List[1 items]");
	}

	@Test
	void logRequest_masksCreatedKeyInQuery() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("GET", "/keys", "10.0.0.1", "createdKey=sk-secret123", null);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("sk-secret123");
		assertThat(msg).contains("createdKey=***");
	}

	@Test
	void logRequest_masksTokenInQuery() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("GET", "/api", "10.0.0.1", "token=abc&page=1", null);
		String msg = lastMessage(appender);
		assertThat(msg).doesNotContain("abc");
		assertThat(msg).contains("token=***");
		assertThat(msg).contains("page=1");
	}

	@Test
	void logRequest_queryParamNoValue() {
		ListAppender<ILoggingEvent> appender = captureLog();
		ActionLogger.logRequest("GET", "/api", "10.0.0.1", "debug", null);
		String msg = lastMessage(appender);
		assertThat(msg).contains("?debug");
	}
}
