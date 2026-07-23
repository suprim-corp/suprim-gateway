package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.proxy.StreamHandler;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KiroFacadeRetryTest {

	private KiroFacade facade;
	private KiroAuthManager authManager;
	private AccountRotator rotator;
	private CredentialStore store;
	private KiroHttpClient kiroClient;
	private KiroUpstreamDispatcher upstreamDispatcher;

	@BeforeEach
	void setUp() throws Exception {
		store = mock(CredentialStore.class);
		authManager = mock(KiroAuthManager.class);
		rotator = mock(AccountRotator.class);
		kiroClient = mock(KiroHttpClient.class);
		PayloadBuilder payloadBuilder = mock(PayloadBuilder.class);
		KiroAccountModelAvailability modelAvailability = mock(KiroAccountModelAvailability.class);
		ModelResolver modelResolver = new ModelResolver();
		StreamConverter streamConverter = new StreamConverter();
		StreamHandler streamHandler = mock(StreamHandler.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		VirtualKeyService keyService = mock(VirtualKeyService.class);

		when(streamHandler.collectContent(any())).thenReturn(
				new StreamHandler.CollectResult("hello", null, 0.0));
		when(streamHandler.countTokens(anyString())).thenReturn(5);
		when(payloadBuilder.buildOpenAiPayload(any(), any())).thenReturn("{\"test\":true}");

		when(modelAvailability.eligibleAccounts(anyString(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
		when(modelAvailability.isWarmUpComplete(anyList())).thenReturn(true);
		upstreamDispatcher = new KiroUpstreamDispatcher(
				kiroClient, payloadBuilder, authManager, rotator, store, modelAvailability, modelResolver
		);
		KiroFormatConverter formatConverter = new KiroFormatConverter(streamConverter);

		facade = new KiroFacade(
				authManager, streamHandler, streamConverter,
				logPublisher, keyService, upstreamDispatcher, formatConverter
		);
	}

	@Test
	void handle_retriesNextAccountOn429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("kiro1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key1")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("kiro2").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key2")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("key1");
		when(authManager.getAccessToken(acc2)).thenReturn("key2");

		KiroHttpClient.KiroResponse resp429 = new KiroHttpClient.KiroResponse(
				429, new ByteArrayInputStream("rate limited".getBytes()), "application/json");
		KiroHttpClient.KiroResponse resp200 = new KiroHttpClient.KiroResponse(
				200, new ByteArrayInputStream("data: {}\n".getBytes()), "text/event-stream");

		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any()))
				.thenReturn(resp429);
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key2"), any()))
				.thenReturn(resp200);

		StreamHandler streamHandler2 = mock(StreamHandler.class);
		when(streamHandler2.collectContent(any())).thenReturn(
				new StreamHandler.CollectResult("hello", null, 0.0));

		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-sonnet-4-20250514")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		MockHttpServletResponse httpRes = new MockHttpServletResponse();
		facade.handle(request, "claude-sonnet-4-20250514", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	@Test
	void handle_singleAccount_noRetryLoop() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("kiro1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key1")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1));

		when(authManager.getAccessToken()).thenReturn("key1");
		when(authManager.isApiKeyAuth()).thenReturn(true);

		KiroHttpClient.KiroResponse resp200 = new KiroHttpClient.KiroResponse(
				200, new ByteArrayInputStream("data: {}\n".getBytes()), "text/event-stream");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any()))
				.thenReturn(resp200);

		InternalRequest request = InternalRequest.builder()
		                                         .model("claude-sonnet-4-20250514")
		                                         .messages(List.of())
		                                         .stream(false)
		                                         .build();

		MockHttpServletResponse httpRes = new MockHttpServletResponse();
		facade.handle(request, "claude-sonnet-4-20250514", false, 10, "key1", "127.0.0.1", Format.COMPLETION, httpRes);

		verify(rotator, never()).next(anyString());
	}
}
