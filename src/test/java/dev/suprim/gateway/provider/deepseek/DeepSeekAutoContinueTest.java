package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DeepSeekAutoContinueTest {

	private MockWebServer server;
	private DeepSeekHttpClient httpClient;
	private DeepSeekAutoContinue autoContinue;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		httpClient = new DeepSeekHttpClient(null);
		String baseUrl = server.url("").toString();
		String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		autoContinue = new DeepSeekAutoContinue(httpClient, url);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void finishedStatus_noContination() {
		String sse = sseLines(
				chunk("Hello", "response/content"),
				status("FINISHED"),
				"[DONE]"
		);
		InputStream input = stream(sse);

		DeepSeekAutoContinue.Result result = autoContinue.process(
				input, "session1", "token1", "pow1"
		);

		List<KiroEvent> events = result.events();
		assertEquals(1, events.size());
		assertEquals("Hello", events.getFirst().content());
		assertEquals("FINISHED", result.status());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	void incompleteStatus_callsContinue() throws Exception {
		String firstStream = sseLines(
				chunk("Part 1", "response/content"),
				"{\"response_message_id\":42,\"v\":\"x\",\"p\":\"response/content\"}",
				status("INCOMPLETE"),
				"[DONE]"
		);
		String continueStream = sseLines(
				chunk("Part 2", "response/content"),
				status("FINISHED"),
				"[DONE]"
		);
		server.enqueue(new MockResponse().setResponseCode(200).setBody(continueStream));

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(firstStream), "session1", "token1", "pow1"
		);

		assertEquals(3, result.events().size());
		assertEquals("Part 1", result.events().get(0).content());
		assertEquals("x", result.events().get(1).content());
		assertEquals("Part 2", result.events().get(2).content());
		assertEquals("FINISHED", result.status());

		RecordedRequest req = server.takeRequest();
		assertEquals("POST", req.getMethod());
		assertTrue(req.getPath().contains("/api/v0/chat/continue"));
		String body = req.getBody().readUtf8();
		assertTrue(body.contains("session1"));
		assertTrue(body.contains("42"));
	}

	@Test
	void autoContinueStatus_callsContinue() {
		String firstStream = sseLines(
				chunk("Start", "response/content"),
				"{\"response_message_id\":10,\"v\":\"\",\"p\":\"\"}",
				status("AUTO_CONTINUE"),
				"[DONE]"
		);
		String continueStream = sseLines(
				chunk("End", "response/content"),
				status("FINISHED"),
				"[DONE]"
		);
		server.enqueue(new MockResponse().setResponseCode(200).setBody(continueStream));

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(firstStream), "sess", "tok", "pow"
		);

		assertEquals(2, result.events().size());
		assertEquals("Start", result.events().get(0).content());
		assertEquals("End", result.events().get(1).content());
		assertEquals("FINISHED", result.status());
	}

	@Test
	void maxRoundsEnforced() {
		StringBuilder firstStream = new StringBuilder();
		firstStream.append(dataLine(chunk("r0", "response/content")));
		firstStream.append(dataLine("{\"response_message_id\":1,\"v\":\"\",\"p\":\"\"}"));
		firstStream.append(dataLine(status("INCOMPLETE")));
		firstStream.append(dataLine("[DONE]"));

		for (int i = 0; i < 8; i++) {
			String continueResponse = sseLines(
					chunk("r" + (i + 1), "response/content"),
					"{\"response_message_id\":" + (i + 2) + ",\"v\":\"\",\"p\":\"\"}",
					status("INCOMPLETE"),
					"[DONE]"
			);
			server.enqueue(new MockResponse().setResponseCode(200).setBody(continueResponse));
		}

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(firstStream.toString()), "sess", "tok", "pow"
		);

		assertEquals(8, server.getRequestCount());
		assertEquals("INCOMPLETE", result.status());
	}

	@Test
	void continueHttpFailure_stopsGracefully() {
		String firstStream = sseLines(
				chunk("ok", "response/content"),
				"{\"response_message_id\":5,\"v\":\"\",\"p\":\"\"}",
				status("INCOMPLETE"),
				"[DONE]"
		);
		server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(firstStream), "sess", "tok", "pow"
		);

		assertEquals(1, result.events().size());
		assertEquals("ok", result.events().getFirst().content());
	}

	@Test
	void contentFilterStatus_stops() {
		String sse = sseLines(
				chunk("partial", "response/content"),
				status("CONTENT_FILTER"),
				"[DONE]"
		);

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(sse), "sess", "tok", "pow"
		);

		assertEquals("CONTENT_FILTER", result.status());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	void noMessageId_doesNotContinue() {
		String sse = sseLines(
				chunk("text", "response/content"),
				status("INCOMPLETE"),
				"[DONE]"
		);

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(sse), "sess", "tok", "pow"
		);

		assertEquals("INCOMPLETE", result.status());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	void constructor_trailingSlashStripped() {
		String sse = sseLines(
				chunk("hi", "response/content"),
				"{\"response_message_id\":1,\"v\":\"\",\"p\":\"\"}",
				status("INCOMPLETE"),
				"[DONE]"
		);
		String continueStream = sseLines(
				chunk("done", "response/content"),
				status("FINISHED"),
				"[DONE]"
		);
		server.enqueue(new MockResponse().setResponseCode(200).setBody(continueStream));

		DeepSeekHttpClient client = new DeepSeekHttpClient(null);
		DeepSeekAutoContinue ac = new DeepSeekAutoContinue(client, server.url("/").toString());
		DeepSeekAutoContinue.Result result = ac.process(stream(sse), "s", "t", "p");

		assertEquals("FINISHED", result.status());
		assertEquals(1, server.getRequestCount());
	}

	@Test
	void continueNullBody_stopsGracefully() throws Exception {
		String firstStream = sseLines(
				chunk("ok", "response/content"),
				"{\"response_message_id\":5,\"v\":\"\",\"p\":\"\"}",
				status("INCOMPLETE"),
				"[DONE]"
		);

		DeepSeekHttpClient mockClient = mock(DeepSeekHttpClient.class);
		Response response = mock(Response.class);
		when(response.isSuccessful()).thenReturn(true);
		when(response.body()).thenReturn(null);
		when(mockClient.execute(any())).thenReturn(response);
		when(mockClient.buildPostRequest(any(), any(), any(), any()))
				.thenReturn(new okhttp3.Request.Builder().url("http://localhost/api/v0/chat/continue").build());

		DeepSeekAutoContinue ac = new DeepSeekAutoContinue(mockClient, "http://localhost");
		DeepSeekAutoContinue.Result result = ac.process(
				stream(firstStream), "sess", "tok", "pow"
		);

		assertEquals(1, result.events().size());
		verify(response).close();
	}

	@Test
	void continueException_stopsGracefully() throws Exception {
		String firstStream = sseLines(
				chunk("ok", "response/content"),
				"{\"response_message_id\":5,\"v\":\"\",\"p\":\"\"}",
				status("INCOMPLETE"),
				"[DONE]"
		);
		server.shutdown();

		DeepSeekAutoContinue.Result result = autoContinue.process(
				stream(firstStream), "sess", "tok", "pow"
		);

		assertEquals(1, result.events().size());
	}

	private InputStream stream(String content) {
		return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	}

	private String chunk(String text, String path) {
		return "{\"v\":\"" + text + "\",\"p\":\"" + path + "\"}";
	}

	private String status(String s) {
		return "{\"v\":\"" + s + "\",\"p\":\"response/status\"}";
	}

	private String dataLine(String json) {
		return "data: " + json + "\n\n";
	}

	private String sseLines(String... chunks) {
		StringBuilder sb = new StringBuilder();
		for (String c : chunks) {
			sb.append("data: ").append(c).append("\n\n");
		}
		return sb.toString();
	}
}
