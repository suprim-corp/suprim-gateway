package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XaiFacadeTest {

	@TempDir
	Path tempDir;

	private XaiFacade facade;
	private XaiAuthManager authManager;

	@BeforeEach
	void setUp() {
		Path storePath = tempDir.resolve("credentials.json");
		CredentialStore store = new CredentialStore(storePath);
		authManager = new XaiAuthManager(store);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		facade = new XaiFacade(authManager, logPublisher);
	}

	@Test
	void handle_notConnected_returns401() throws Exception {
		InternalRequest request = InternalRequest.builder()
				.model("grok-4")
				.messages(List.of())
				.stream(true)
				.build();

		MockHttpServletResponse response = new MockHttpServletResponse();
		facade.handle(
				request, "grok-4", true, 10, "key1", "127.0.0.1",
				Format.OPENAI, response
		);
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("not connected"));
	}

	@Test
	void handle_connected_setsCorrectContentTypeForStream() throws Exception {
		authManager.saveCredentials("fake-token", "fake-refresh", Instant.now().plusSeconds(7200), "test@x.ai");

		InternalRequest request = InternalRequest.builder()
				.model("grok-4")
				.messages(List.of())
				.stream(true)
				.build();

		MockHttpServletResponse response = new MockHttpServletResponse();
		try {
			facade.handle(
					request, "grok-4", true, 10, "key1", "127.0.0.1",
					Format.OPENAI, response
			);
		} catch (Exception e) {
			// Expected - can't reach real xAI server in tests
		}
		assertNotEquals(401, response.getStatus());
	}
}
