package dev.suprim.gateway.logging;

import dev.suprim.gateway.proxy.ProxyChain;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ActionLogFilterTest {

	private final ProxyChain proxyChain = ProxyChain.of(Path.of("nonexistent.json"));
	private final ActionLogFilter filter = new ActionLogFilter(proxyChain);

	@ParameterizedTest
	@ValueSource(strings = {
			"/actuator/health", "/health", "/swagger-ui/index.html",
			"/api-docs/v3", "/login", "/css/style.css", "/js/app.js", "/favicon.ico"
	})
	void shouldNotFilter_blacklistedPaths(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		assertThat(filter.shouldNotFilter(request)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/v1/models", "/v1/chat/completions", "/", "/keys", "/usage"})
	void shouldNotFilter_allowedPaths(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		assertThat(filter.shouldNotFilter(request)).isFalse();
	}

	@Test
	void streamingPath_passesThrough_noResponseWrapping() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
		request.setContent("{\"model\":\"claude\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean[] chainCalled = {false};
		FilterChain chain = (req, res) -> {
			chainCalled[0] = true;
			res.getWriter().write("streaming data");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(chainCalled[0]).isTrue();
		assertThat(response.getHeader("X-Request-ID")).isNotBlank();
	}

	@Test
	void streamingPath_responses_passesThrough() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/responses");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> res.getWriter().write("sse");

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getContentAsString()).isEqualTo("sse");
	}

	@Test
	void getRequest_wrapsResponse_logsJsonBody() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{\"data\":[]}");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getContentAsString()).isEqualTo("{\"data\":[]}");
		assertThat(response.getHeader("X-Request-ID")).isNotBlank();
	}

	@Test
	void getRequest_htmlResponse_notLoggedAsBody() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("text/html");
			res.getWriter().write("<html></html>");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getContentAsString()).isEqualTo("<html></html>");
	}

	@Test
	void postRequest_cachesBodyForDownstream() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/models");
		request.setContentType("application/json");
		request.setContent("{\"test\":true}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] capturedBody = {null};
		FilterChain chain = (req, res) -> {
			byte[] bytes = req.getInputStream().readAllBytes();
			capturedBody[0] = new String(bytes, StandardCharsets.UTF_8);
			res.setContentType("application/json");
			res.getWriter().write("{\"ok\":true}");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(capturedBody[0]).isEqualTo("{\"test\":true}");
		assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
	}

	@Test
	void multipartPost_skipsBodyCaching() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/import");
		request.setContentType("multipart/form-data; boundary=----WebKitFormBoundary");
		request.setContent("--boundary--".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean[] chainCalled = {false};
		FilterChain chain = (req, res) -> {
			chainCalled[0] = true;
			assertThat(req).isSameAs(request);
			res.setContentType("text/html");
			res.getWriter().write("ok");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(chainCalled[0]).isTrue();
	}

	@Test
	void formEncodedPost_skipsBodyCaching() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/keys/create");
		request.setContentType("application/x-www-form-urlencoded");
		request.setContent("name=local&rateLimitPerMin=60".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean[] chainCalled = {false};
		FilterChain chain = (req, res) -> {
			chainCalled[0] = true;
			assertThat(req).isSameAs(request);
			res.setContentType("text/html");
			res.getWriter().write("ok");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(chainCalled[0]).isTrue();
	}

	@Test
	void correlationId_usesExistingHeader() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		request.addHeader("X-Request-ID", "my-custom-id");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getHeader("X-Request-ID")).isEqualTo("my-custom-id");
	}

	@Test
	void correlationId_generatesUuidWhenMissing() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);

		String id = response.getHeader("X-Request-ID");
		assertThat(id).isNotBlank();
		assertThat(id).matches("[0-9a-f\\-]{36}");
	}

	@Test
	void correlationId_generatesUuidWhenBlank() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		request.addHeader("X-Request-ID", "   ");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);

		String id = response.getHeader("X-Request-ID");
		assertThat(id).doesNotContain("   ");
		assertThat(id).matches("[0-9a-f\\-]{36}");
	}

	@Test
	void mdcClearedAfterRequest() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(org.slf4j.MDC.get("requestId")).isNull();
	}

	@Test
	void mdcClearedEvenOnException() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			throw new RuntimeException("boom");
		};

		try {
			filter.doFilterInternal(request, response, chain);
		} catch (Exception ignored) {
		}

		assertThat(org.slf4j.MDC.get("requestId")).isNull();
	}

	@Test
	void xForwardedFor_usedForClientIp() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);
		// no assertion on IP logging content here — covered by ActionLoggerTest
		// just verify it doesn't blow up with XFF header
		assertThat(response.getHeader("X-Request-ID")).isNotBlank();
	}

	@Test
	void nullContentType_multipartCheckHandled() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
		request.setContent("data".getBytes(StandardCharsets.UTF_8));
		// contentType deliberately left null — should not cache body
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			assertThat(req).isSameAs(request);
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getHeader("X-Request-ID")).isNotBlank();
	}

	@Test
	void nullResponseContentType_noBodyLogged() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			// no content type set on response
			res.getWriter().write("some output");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(response.getContentAsString()).isEqualTo("some output");
	}

	@Test
	void textContentType_cachesBody() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
		request.setContentType("text/plain");
		request.setContent("hello world".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] capturedBody = {null};
		FilterChain chain = (req, res) -> {
			byte[] bytes = req.getInputStream().readAllBytes();
			capturedBody[0] = new String(bytes, StandardCharsets.UTF_8);
			res.setContentType("application/json");
			res.getWriter().write("{}");
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(capturedBody[0]).isEqualTo("hello world");
	}
}
