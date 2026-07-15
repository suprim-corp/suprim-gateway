package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.ProxyEntry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekHttpClientTest {

	@Test
	void createsClientWithoutProxy() {
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		OkHttpClient okHttp = httpClient.rawClient();
		assertNotNull(okHttp);
	}

	@Test
	void createsClientWithSocks5Proxy() {
		ProxyEntry entry = ProxyEntry.builder()
				.scheme(ProxyEntry.Scheme.SOCKS5)
				.host("127.0.0.1")
				.port(1080)
				.build();
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(entry);
		assertNotNull(httpClient.rawClient());
		assertNotNull(httpClient.rawClient().proxy());
	}

	@Test
	void createsClientWithHttpProxy() {
		ProxyEntry entry = ProxyEntry.builder()
				.scheme(ProxyEntry.Scheme.HTTP)
				.host("127.0.0.1")
				.port(8080)
				.build();
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(entry);
		assertNotNull(httpClient.rawClient());
	}

	@Test
	void buildPostRequest_containsRequiredHeaders() {
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		Request request = httpClient.buildPostRequest(
				"https://chat.deepseek.com/api/v0/chat/completion",
				"{\"prompt\":\"hi\"}",
				"fake-token",
				"pow-header-value"
		);

		assertEquals("POST", request.method());
		assertEquals("Bearer fake-token", request.header("Authorization"));
		assertEquals("pow-header-value", request.header("x-ds-pow-response"));
		assertEquals("application/json", request.header("Content-Type"));
		assertNotNull(request.header("User-Agent"));
		assertNotNull(request.header("x-client-platform"));
	}

	@Test
	void buildPostRequest_withoutPow_omitsPowHeader() {
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		Request request = httpClient.buildPostRequest(
				"https://chat.deepseek.com/api/v0/users/login",
				"{\"email\":\"test\"}",
				null,
				null
		);

		assertNull(request.header("x-ds-pow-response"));
		assertNull(request.header("Authorization"));
	}

	@Test
	void execute_returnsResponse() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setBody("{\"ok\":true}").setResponseCode(200));
			server.start();

			DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
			Request request = httpClient.buildPostRequest(
					server.url("/api/v0/users/login").toString(),
					"{\"email\":\"test\"}",
					null,
					null
			);

			try (Response response = httpClient.execute(request)) {
				assertEquals(200, response.code());
				assertNotNull(response.body());
			}
		}
	}

	@Test
	void executeStream_returnsInputStream() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setBody("data: hello\n\n").setResponseCode(200));
			server.start();

			DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
			Request request = httpClient.buildPostRequest(
					server.url("/api/v0/chat/completion").toString(),
					"{\"prompt\":\"hi\"}",
					"token",
					"pow"
			);

			InputStream stream = httpClient.executeStream(request);
			assertNotNull(stream);
			String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(content.contains("hello"));
			stream.close();
		}
	}

	@Test
	void executeStream_emptyBody_returnsStream() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			MockResponse emptyResponse = new MockResponse()
					.setResponseCode(200)
					.setHeader("Content-Length", "0");
			server.enqueue(emptyResponse);
			server.start();

			DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
			Request request = httpClient.buildPostRequest(
					server.url("/test").toString(),
					"{}",
					null,
					null
			);

			InputStream stream = httpClient.executeStream(request);
			assertNotNull(stream);
			stream.close();
		}
	}

	@Test
	void executeStream_nullBody_throwsIOException() throws IOException {
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);

		Response response = mock(Response.class);
		when(response.body()).thenReturn(null);

		assertThrows(IOException.class, () -> httpClient.extractBody(response));
		verify(response).close();
	}
}
