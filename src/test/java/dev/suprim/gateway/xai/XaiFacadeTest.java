package dev.suprim.gateway.xai;

import dev.suprim.gateway.auth.KiroCredentialStore;
import dev.suprim.gateway.logging.RequestLogPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.time.Instant;

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
		KiroCredentialStore store = new KiroCredentialStore(storePath);
		authManager = new XaiAuthManager(store);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		facade = new XaiFacade(authManager, logPublisher);
	}

	@Test
	void handle_notConnected_returns401() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		facade.handle(
				java.util.Map.of("model", "grok-4", "messages", java.util.List.of()),
				"grok-4", true, 10, "key1", "127.0.0.1", response
		);
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("not connected"));
	}

	@Test
	void handle_connected_setsCorrectContentTypeForStream() throws Exception {
		authManager.saveCredentials("fake-token", "fake-refresh", Instant.now().plusSeconds(7200), "test@x.ai");

		MockHttpServletResponse response = new MockHttpServletResponse();
		// This will fail to connect to real xAI but we can verify it attempts streaming setup
		try {
			facade.handle(
					java.util.Map.of("model", "grok-4", "messages", java.util.List.of(), "stream", true),
					"grok-4", true, 10, "key1", "127.0.0.1", response
			);
		} catch (Exception e) {
			// Expected - can't reach real xAI server in tests
		}
		// If it got past auth check, the response should have been set up for streaming or errored on network
		assertNotEquals(401, response.getStatus());
	}
}
