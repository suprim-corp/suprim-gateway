package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.StreamConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntigravityFacadeRetryTest {

	private AntigravityFacade facade;
	private AntigravityAuthManager authManager;
	private AccountRotator rotator;
	private CredentialStore store;

	@BeforeEach
	void setUp() {
		store = mock(CredentialStore.class);
		authManager = mock(AntigravityAuthManager.class);
		rotator = mock(AccountRotator.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		StreamConverter streamConverter = new StreamConverter();
		facade = new AntigravityFacade(
				authManager, logPublisher, streamConverter, rotator, store,
				new AntigravityAccountCooldown()
		);
	}

	@Test
	void handle_retriesNextAccountOn429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("acc1").provider("ANTIGRAVITY")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .projectId("proj1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("acc2").provider("ANTIGRAVITY")
		                                   .accessToken("tok2").refreshToken("ref2")
		                                   .projectId("proj2")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();

		when(store.findAllByProvider("ANTIGRAVITY")).thenReturn(List.of(acc1, acc2));
		when(rotator.next("ANTIGRAVITY")).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("tok1");
		when(authManager.getAccessToken(acc2)).thenReturn("tok2");
		when(authManager.getProjectId(acc1)).thenReturn("proj1");
		when(authManager.getProjectId(acc2)).thenReturn("proj2");

		InternalRequest request = InternalRequest.builder()
		                                         .model("gemini-2.5-pro")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<AntigravityHttpClient> mocked = mockStatic(AntigravityHttpClient.class)) {
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok1")))
			      .thenReturn(new AntigravityHttpClient.AntigravityResponse(429, new ByteArrayInputStream("rate limited".getBytes())));
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok2")))
			      .thenReturn(new AntigravityHttpClient.AntigravityResponse(200, new ByteArrayInputStream("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n".getBytes())));

			MockHttpServletResponse httpRes = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

			assertEquals(200, httpRes.getStatus());
		}
	}

	@Test
	void handle_skipsCooledAccountOnLaterRequest() throws Exception {
		StoredAccount limited = StoredAccount.builder()
		                                   .name("limited").provider("ANTIGRAVITY")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .projectId("proj1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		StoredAccount healthy = StoredAccount.builder()
		                                   .name("healthy").provider("ANTIGRAVITY")
		                                   .accessToken("tok2").refreshToken("ref2")
		                                   .projectId("proj2")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		when(store.findAllByProvider("ANTIGRAVITY")).thenReturn(List.of(limited, healthy));
		when(rotator.next("ANTIGRAVITY")).thenReturn(limited, healthy, limited, healthy);
		when(authManager.getAccessToken(limited)).thenReturn("tok1");
		when(authManager.getAccessToken(healthy)).thenReturn("tok2");
		when(authManager.getProjectId(limited)).thenReturn("proj1");
		when(authManager.getProjectId(healthy)).thenReturn("proj2");

		InternalRequest request = InternalRequest.builder()
		                                         .model("gemini-2.5-pro")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<AntigravityHttpClient> mocked = mockStatic(AntigravityHttpClient.class)) {
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok1")))
			      .thenAnswer(ignored -> new AntigravityHttpClient.AntigravityResponse(429, new ByteArrayInputStream("rate limited".getBytes())));
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok2")))
			      .thenAnswer(ignored -> new AntigravityHttpClient.AntigravityResponse(200, new ByteArrayInputStream("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n".getBytes())));

			MockHttpServletResponse firstResponse = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, firstResponse);
			MockHttpServletResponse secondResponse = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, secondResponse);

			assertEquals(200, firstResponse.getStatus());
			assertEquals(200, secondResponse.getStatus());
			mocked.verify(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok1")), times(1));
			mocked.verify(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok2")), times(2));
		}
	}

	@Test
	void handle_coolsAccountAfter503() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                   .name("unavailable").provider("ANTIGRAVITY")
		                                   .accessToken("tok").refreshToken("ref")
		                                   .projectId("project")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		when(store.findAllByProvider("ANTIGRAVITY")).thenReturn(List.of(account));
		when(rotator.next("ANTIGRAVITY")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("tok");
		when(authManager.getProjectId(account)).thenReturn("project");

		InternalRequest request = InternalRequest.builder()
		                                         .model("gemini-2.5-pro")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<AntigravityHttpClient> mocked = mockStatic(AntigravityHttpClient.class)) {
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok")))
			      .thenAnswer(ignored -> new AntigravityHttpClient.AntigravityResponse(503, new ByteArrayInputStream("unavailable".getBytes())));

			MockHttpServletResponse firstResponse = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, firstResponse);
			MockHttpServletResponse secondResponse = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, secondResponse);

			assertEquals(429, firstResponse.getStatus());
			assertEquals(429, secondResponse.getStatus());
			assertTrue(secondResponse.getContentAsString().contains("rate_limit_exhausted"));
			mocked.verify(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok")), times(1));
		}
	}

	@Test
	void handle_allAccountsExhausted_returns429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("acc1").provider("ANTIGRAVITY")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .projectId("proj1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();

		when(store.findAllByProvider("ANTIGRAVITY")).thenReturn(List.of(acc1));
		when(rotator.next("ANTIGRAVITY")).thenReturn(acc1);
		when(authManager.getAccessToken(acc1)).thenReturn("tok1");
		when(authManager.getProjectId(acc1)).thenReturn("proj1");

		InternalRequest request = InternalRequest.builder()
		                                         .model("gemini-2.5-pro")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<AntigravityHttpClient> mocked = mockStatic(AntigravityHttpClient.class)) {
			mocked.when(() -> AntigravityHttpClient.call(eq("gemini-2.5-pro"), anyString(), eq("tok1")))
			      .thenReturn(new AntigravityHttpClient.AntigravityResponse(429, new ByteArrayInputStream("rate limited".getBytes())));

			MockHttpServletResponse httpRes = new MockHttpServletResponse();
			facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

			assertEquals(429, httpRes.getStatus());
			assertTrue(httpRes.getContentAsString().contains("rate_limit_exhausted"));
		}
	}

	@Test
	void handle_noAccounts_returns401() throws Exception {
		when(store.findAllByProvider("ANTIGRAVITY")).thenReturn(List.of());

		InternalRequest request = InternalRequest.builder()
		                                         .model("gemini-2.5-pro")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		MockHttpServletResponse httpRes = new MockHttpServletResponse();
		facade.handle(request, "gemini-2.5-pro", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(401, httpRes.getStatus());
	}
}
