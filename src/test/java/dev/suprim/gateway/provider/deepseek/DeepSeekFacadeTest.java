package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.provider.StoredAccount;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DeepSeekFacadeTest {

	private MockWebServer server;
	private DeepSeekFacade facade;
	private MockHttpServletResponse httpRes;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		String baseUrl = server.url("").toString();
		baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		DeepSeekAuthManager authManager = new DeepSeekAuthManager(httpClient, baseUrl);
		StoredAccount account = StoredAccount.builder()
				.name("test@ds.com")
				.accessToken("pass123")
				.provider("DEEPSEEK")
				.build();
		DeepSeekAccountPool pool = new DeepSeekAccountPool(List.of(account), 2);
		DeepSeekAutoContinue autoContinue = new DeepSeekAutoContinue(httpClient, baseUrl);

		facade = new DeepSeekFacade(httpClient, authManager, pool, autoContinue, new StreamConverter(), baseUrl);
		httpRes = new MockHttpServletResponse();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void handle_happyPath_streamsContent() throws Exception {
		enqueueLogin("tok123");
		enqueueCreateSession("sess-1");
		enqueuePowChallenge();
		enqueueCompletion(sseLines(
				chunk("Hello world", "response/content"),
				status("FINISHED"),
				"[DONE]"
		));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(200, httpRes.getStatus());
		String body = httpRes.getContentAsString();
		assertTrue(body.contains("Hello world"));
	}

	@Test
	void handle_noAccounts_returns429() throws Exception {
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		DeepSeekAuthManager authManager = new DeepSeekAuthManager(httpClient, "http://localhost");
		DeepSeekAccountPool emptyPool = new DeepSeekAccountPool(List.of(), 2);
		DeepSeekAutoContinue autoContinue = new DeepSeekAutoContinue(httpClient, "http://localhost");
		DeepSeekFacade emptyFacade = new DeepSeekFacade(httpClient, authManager, emptyPool, autoContinue, new StreamConverter(), "http://localhost");

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.stream(true)
				.build();

		emptyFacade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_authFailure_returns401() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"code\":\"unauthorized\"}"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "Hi")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_emptyOutput_retriesSameAccount() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		enqueuePowChallenge();
		enqueueCompletion(sseLines(status("FINISHED"), "[DONE]"));
		// retry: re-fetch pow + completion
		enqueuePowChallenge();
		enqueueCompletion(sseLines(
				chunk("retry worked", "response/content"),
				status("FINISHED"),
				"[DONE]"
		));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(200, httpRes.getStatus());
		assertTrue(httpRes.getContentAsString().contains("retry worked"));
	}

	@Test
	void handle_allRetriesEmpty_returns429() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		// 3 empty completions
		enqueuePowChallenge();
		enqueueCompletion(sseLines(status("FINISHED"), "[DONE]"));
		enqueuePowChallenge();
		enqueueCompletion(sseLines(status("FINISHED"), "[DONE]"));
		enqueuePowChallenge();
		enqueueCompletion(sseLines(status("FINISHED"), "[DONE]"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		// attemptCompletion returns false after 3 empties, adds to triedSet, no more accounts → 429
		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_createSessionFails_returns429() throws Exception {
		enqueueLogin("tok1");
		server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_powUnsolvable_returns429() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		// PoW with impossible challenge
		server.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{\"algorithm\":\"DeepSeekHashV1\",\"challenge\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"salt\":\"s\",\"difficulty\":1,\"expire_at\":1700000000,\"signature\":\"sig\",\"target_path\":\"/api/v0/chat/completion\"}}}"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void constructor_withTrailingSlash() throws Exception {
		DeepSeekHttpClient client = new DeepSeekHttpClient(null);
		DeepSeekAuthManager auth = new DeepSeekAuthManager(client, server.url("").toString());
		StoredAccount account = StoredAccount.builder()
				.name("t@t.com").accessToken("p").provider("DEEPSEEK").build();
		DeepSeekAccountPool pool = new DeepSeekAccountPool(List.of(account), 2);
		DeepSeekAutoContinue ac = new DeepSeekAutoContinue(client, server.url("").toString());
		DeepSeekFacade f = new DeepSeekFacade(client, auth, pool, ac, new StreamConverter(), server.url("/").toString());

		enqueueLogin("tok");
		enqueueCreateSession("s");
		enqueuePowChallenge();
		enqueueCompletion(sseLines(chunk("ok", "response/content"), status("FINISHED"), "[DONE]"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "x")))
				.stream(true)
				.build();

		MockHttpServletResponse res = new MockHttpServletResponse();
		f.handle(request, "deepseek/v4-flash", true, 1, "k", "1.1.1.1", Format.COMPLETION, res);
		assertEquals(200, res.getStatus());
	}

	@Test
	void handle_createSessionNullBody_returns429() throws Exception {
		enqueueLogin("tok1");
		server.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Length", "0").setBody(""));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_powHttpFailure_returns429() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		server.enqueue(new MockResponse().setResponseCode(500).setBody("pow error"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_createSessionMissingId_returns429() throws Exception {
		enqueueLogin("tok1");
		server.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{}}}"));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_emptyEventsList_returns429() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		enqueuePowChallenge();
		// Stream with no content events at all — just DONE
		enqueueCompletion("data: [DONE]\n\n");
		enqueuePowChallenge();
		enqueueCompletion("data: [DONE]\n\n");
		enqueuePowChallenge();
		enqueueCompletion("data: [DONE]\n\n");

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(429, httpRes.getStatus());
	}

	@Test
	void handle_eventWithNullContent_writesOnlyNonNull() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		enqueuePowChallenge();
		// reasoning event (type != "content") + content event
		enqueueCompletion(sseLines(
				"{\"v\":\"think\",\"p\":\"response/thinking_content\"}",
				chunk("visible", "response/content"),
				status("FINISHED"),
				"[DONE]"
		));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(200, httpRes.getStatus());
		String body = httpRes.getContentAsString();
		assertTrue(body.contains("visible"));
		assertTrue(body.contains("reasoning_content"));
		assertTrue(body.contains("think"));
	}

	@Test
	void handle_createSessionNullResponseBody_returns429() throws Exception {
		DeepSeekHttpClient mockClient = mock(DeepSeekHttpClient.class);
		Response loginResp = mock(Response.class);
		when(loginResp.isSuccessful()).thenReturn(true);
		when(loginResp.body()).thenReturn(okhttp3.ResponseBody.create(
				"{\"data\":{\"biz_data\":{\"user\":{\"token\":\"tok\"}}}}",
				okhttp3.MediaType.get("application/json")));

		Response sessionResp = mock(Response.class);
		when(sessionResp.isSuccessful()).thenReturn(true);
		when(sessionResp.body()).thenReturn(null);

		when(mockClient.buildPostRequest(any(), any(), any(), any()))
				.thenReturn(new okhttp3.Request.Builder().url("http://localhost/api").build());
		when(mockClient.execute(any())).thenReturn(loginResp, sessionResp);

		DeepSeekAuthManager auth = new DeepSeekAuthManager(mockClient, "http://localhost");
		StoredAccount account = StoredAccount.builder().name("x@x.com").accessToken("p").provider("DEEPSEEK").build();
		DeepSeekAccountPool pool = new DeepSeekAccountPool(List.of(account), 2);
		DeepSeekAutoContinue ac = new DeepSeekAutoContinue(mockClient, "http://localhost");
		DeepSeekFacade f = new DeepSeekFacade(mockClient, auth, pool, ac, new StreamConverter(), "http://localhost");

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		MockHttpServletResponse res = new MockHttpServletResponse();
		f.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, res);
		assertEquals(429, res.getStatus());
		verify(sessionResp).close();
	}

	@Test
	void handle_powNullResponseBody_returns429() throws Exception {
		DeepSeekHttpClient mockClient = mock(DeepSeekHttpClient.class);
		Response loginResp = mock(Response.class);
		when(loginResp.isSuccessful()).thenReturn(true);
		when(loginResp.body()).thenReturn(okhttp3.ResponseBody.create(
				"{\"data\":{\"biz_data\":{\"user\":{\"token\":\"tok\"}}}}",
				okhttp3.MediaType.get("application/json")));

		Response sessionResp = mock(Response.class);
		when(sessionResp.isSuccessful()).thenReturn(true);
		when(sessionResp.body()).thenReturn(okhttp3.ResponseBody.create(
				"{\"data\":{\"biz_data\":{\"id\":\"sess\"}}}",
				okhttp3.MediaType.get("application/json")));

		Response powResp = mock(Response.class);
		when(powResp.isSuccessful()).thenReturn(true);
		when(powResp.body()).thenReturn(null);

		when(mockClient.buildPostRequest(any(), any(), any(), any()))
				.thenReturn(new okhttp3.Request.Builder().url("http://localhost/api").build());
		when(mockClient.execute(any())).thenReturn(loginResp, sessionResp, powResp);

		DeepSeekAuthManager auth = new DeepSeekAuthManager(mockClient, "http://localhost");
		StoredAccount account = StoredAccount.builder().name("x@x.com").accessToken("p").provider("DEEPSEEK").build();
		DeepSeekAccountPool pool = new DeepSeekAccountPool(List.of(account), 2);
		DeepSeekAutoContinue ac = new DeepSeekAutoContinue(mockClient, "http://localhost");
		DeepSeekFacade f = new DeepSeekFacade(mockClient, auth, pool, ac, new StreamConverter(), "http://localhost");

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		MockHttpServletResponse res = new MockHttpServletResponse();
		f.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, res);
		assertEquals(429, res.getStatus());
		verify(powResp).close();
	}

	@Test
	void handle_contentEventWithNullContent_skipped() throws Exception {
		enqueueLogin("tok1");
		enqueueCreateSession("sess-1");
		enqueuePowChallenge();
		// A content event where v is null won't produce KiroEvent, but let's test
		// event with empty path (non content/thinking) that still produces content type
		enqueueCompletion(sseLines(
				"{\"v\":null,\"p\":\"response/content\"}",
				chunk("real", "response/content"),
				status("FINISHED"),
				"[DONE]"
		));

		InternalRequest request = InternalRequest.builder()
				.model("deepseek/v4-flash")
				.messages(List.of(Message.of("user", "test")))
				.stream(true)
				.build();

		facade.handle(request, "deepseek/v4-flash", true, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(200, httpRes.getStatus());
		assertTrue(httpRes.getContentAsString().contains("real"));
	}

	private void enqueueLogin(String token) {
		server.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{\"user\":{\"token\":\"" + token + "\"}}}}"));
	}

	private void enqueueCreateSession(String sessionId) {
		server.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{\"chat_session\":{\"id\":\"" + sessionId + "\"}}}}"));
	}

	private void enqueuePowChallenge() {
		String challenge = DeepSeekPowSolver.hashHex("salt_1700000000_0");
		server.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{\"challenge\":{\"algorithm\":\"DeepSeekHashV1\",\"challenge\":\""
						+ challenge + "\",\"salt\":\"salt\",\"difficulty\":10,\"expire_at\":1700000000,\"signature\":\"sig\",\"target_path\":\"/api/v0/chat/completion\"}}}}"));
	}

	private void enqueueCompletion(String sseBody) {
		server.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Type", "text/event-stream")
				.setBody(sseBody));
	}

	private String chunk(String text, String path) {
		return "{\"v\":\"" + text + "\",\"p\":\"" + path + "\"}";
	}

	private String status(String s) {
		return "{\"v\":\"" + s + "\",\"p\":\"response/status\"}";
	}

	private String sseLines(String... chunks) {
		StringBuilder sb = new StringBuilder();
		for (String c : chunks) {
			sb.append("data: ").append(c).append("\n\n");
		}
		return sb.toString();
	}
}
