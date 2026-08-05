package dev.suprim.gateway.config;

import dev.suprim.gateway.virtualkey.VirtualKeyAuthFilter;
import dev.suprim.gateway.virtualkey.VirtualKeyService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Browser-hosted clients such as Claude for Office cannot reach /v1 at all unless preflight
 * OPTIONS bypasses key auth and the response advertises the headers those clients send.
 */
class ApiCorsPreflightTest {

	private static final String OFFICE_ORIGIN = "https://appsforoffice.microsoft.com";

	private final AppConfig appConfig = new AppConfig(
			"admin-key", null, null, null, null, null, null, null, 0, 0, 0, null);

	@Test
	void preflightSkipsKeyAuthAndReachesTheChain() throws Exception {
		VirtualKeyAuthFilter filter = new VirtualKeyAuthFilter(appConfig, mock(VirtualKeyService.class));

		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/messages");
		request.addHeader("Origin", OFFICE_ORIGIN);
		request.addHeader("Access-Control-Request-Method", "POST");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(200, response.getStatus(), "preflight carries no key, so it must not be rejected");
		assertNotNull(chain.getRequest(), "preflight must pass through to the CORS handler");
	}

	@Test
	void authenticatedMethodsStillRequireAKey() throws Exception {
		VirtualKeyAuthFilter filter = new VirtualKeyAuthFilter(appConfig, mock(VirtualKeyService.class));

		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/messages");
		request.addHeader("Origin", OFFICE_ORIGIN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(401, response.getStatus());
		assertNull(chain.getRequest(), "unauthenticated POST must not reach the chain");
	}

	@Test
	void corsConfigAcceptsOfficeOriginAndItsHeaders() {
		CorsConfigurationSource source =
				new SecurityConfig(appConfig, mock(VirtualKeyAuthFilter.class)).apiCorsConfigurationSource();

		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/messages");
		CorsConfiguration config = source.getCorsConfiguration(request);

		assertNotNull(config, "/v1/** must have a CORS configuration");
		assertNotNull(config.checkOrigin(OFFICE_ORIGIN));
		assertNotNull(config.checkHttpMethod(HttpMethod.OPTIONS));
		assertNotNull(config.checkHttpMethod(HttpMethod.POST));
		assertNotNull(config.checkHeaders(List.of("x-api-key", "anthropic-version", "content-type")));
	}
}
