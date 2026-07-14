package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.OpenAiRelayHandler;
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

class XaiFacadeRetryTest {

	private XaiFacade facade;
	private XaiAuthManager authManager;
	private AccountRotator rotator;
	private CredentialStore store;

	@BeforeEach
	void setUp() {
		store = mock(CredentialStore.class);
		authManager = mock(XaiAuthManager.class);
		rotator = mock(AccountRotator.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		OpenAiRelayHandler relayHandler = new OpenAiRelayHandler(new StreamConverter());
		facade = new XaiFacade(authManager, logPublisher, relayHandler, rotator, store);
	}

	@Test
	void handle_retriesNextAccountOn429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("acc1").provider("XAI")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("acc2").provider("XAI")
		                                   .accessToken("tok2").refreshToken("ref2")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();

		when(store.findAllByProvider("XAI")).thenReturn(List.of(acc1, acc2));
		when(rotator.next("XAI")).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("tok1");
		when(authManager.getAccessToken(acc2)).thenReturn("tok2");

		InternalRequest request = InternalRequest.builder()
		                                         .model("grok-4")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<XaiHttpClient> mocked = mockStatic(XaiHttpClient.class)) {
			mocked.when(() -> XaiHttpClient.call(anyString(), eq("tok1")))
			      .thenReturn(new XaiHttpClient.XaiResponse(429, new ByteArrayInputStream("rate limited".getBytes())));
			mocked.when(() -> XaiHttpClient.call(anyString(), eq("tok2")))
			      .thenReturn(new XaiHttpClient.XaiResponse(200, new ByteArrayInputStream("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}".getBytes())));

			MockHttpServletResponse httpRes = new MockHttpServletResponse();
			facade.handle(request, "grok-4", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

			assertEquals(200, httpRes.getStatus());
		}
	}

	@Test
	void handle_allAccountsExhausted_returns429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("acc1").provider("XAI")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();

		when(store.findAllByProvider("XAI")).thenReturn(List.of(acc1));
		when(rotator.next("XAI")).thenReturn(acc1);
		when(authManager.getAccessToken(acc1)).thenReturn("tok1");

		InternalRequest request = InternalRequest.builder()
		                                         .model("grok-4")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<XaiHttpClient> mocked = mockStatic(XaiHttpClient.class)) {
			mocked.when(() -> XaiHttpClient.call(anyString(), eq("tok1")))
			      .thenReturn(new XaiHttpClient.XaiResponse(429, new ByteArrayInputStream("rate limited".getBytes())));

			MockHttpServletResponse httpRes = new MockHttpServletResponse();
			facade.handle(request, "grok-4", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

			assertEquals(429, httpRes.getStatus());
			assertTrue(httpRes.getContentAsString().contains("rate_limit_exhausted"));
		}
	}

	@Test
	void handle_noAccounts_returns401() throws Exception {
		when(store.findAllByProvider("XAI")).thenReturn(List.of());

		InternalRequest request = InternalRequest.builder()
		                                         .model("grok-4")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		MockHttpServletResponse httpRes = new MockHttpServletResponse();
		facade.handle(request, "grok-4", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		assertEquals(401, httpRes.getStatus());
	}

	@Test
	void handle_nonRetryableError_returnsImmediately() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("acc1").provider("XAI")
		                                   .accessToken("tok1").refreshToken("ref1")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("acc2").provider("XAI")
		                                   .accessToken("tok2").refreshToken("ref2")
		                                   .expiresAt(Instant.now().plusSeconds(3600))
		                                   .build();

		when(store.findAllByProvider("XAI")).thenReturn(List.of(acc1, acc2));
		when(rotator.next("XAI")).thenReturn(acc1);
		when(authManager.getAccessToken(acc1)).thenReturn("tok1");

		InternalRequest request = InternalRequest.builder()
		                                         .model("grok-4")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		try (MockedStatic<XaiHttpClient> mocked = mockStatic(XaiHttpClient.class)) {
			mocked.when(() -> XaiHttpClient.call(anyString(), eq("tok1")))
			      .thenReturn(new XaiHttpClient.XaiResponse(400, new ByteArrayInputStream("bad request".getBytes())));

			MockHttpServletResponse httpRes = new MockHttpServletResponse();
			facade.handle(request, "grok-4", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

			assertEquals(400, httpRes.getStatus());
			verify(rotator, times(1)).next("XAI");
		}
	}
}
