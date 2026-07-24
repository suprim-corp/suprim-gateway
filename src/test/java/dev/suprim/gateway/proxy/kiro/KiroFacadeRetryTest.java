package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KiroFacadeRetryTest {

	private KiroFacade facade;
	private KiroAuthManager authManager;
	private AccountRotator rotator;
	private CredentialStore store;
	private KiroHttpClient kiroClient;
	private ApplicationEventPublisher eventPublisher;

	@BeforeEach
	void setUp() throws Exception {
		store = mock(CredentialStore.class);
		authManager = mock(KiroAuthManager.class);
		rotator = mock(AccountRotator.class);
		kiroClient = mock(KiroHttpClient.class);
		PayloadBuilder payloadBuilder = mock(PayloadBuilder.class);
		KiroAccountModelAvailability modelAvailability = mock(
				KiroAccountModelAvailability.class
		);
		ModelResolver modelResolver = new ModelResolver();
		StreamConverter streamConverter = new StreamConverter();
		StreamHandler streamHandler = mock(StreamHandler.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		RequestLogPublisher logPublisher = new RequestLogPublisher(eventPublisher);
		VirtualKeyService keyService = mock(VirtualKeyService.class);

		when(streamHandler.collectContent(any())).thenReturn(
				new StreamHandler.CollectResult("hello", null, 0.0));
		when(streamHandler.countTokens(anyString())).thenReturn(5);
		when(payloadBuilder.buildOpenAiPayload(any(), any())).thenReturn("{\"test\":true}");
		when(modelAvailability.eligibleAccounts(anyString(), anyList())).thenAnswer(
				invocation -> invocation.getArgument(1)
		);
		when(modelAvailability.isWarmUpComplete(anyList())).thenReturn(true);
		KiroUpstreamDispatcher upstreamDispatcher = new KiroUpstreamDispatcher(
				kiroClient,
				payloadBuilder,
				authManager,
				rotator,
				store,
				modelAvailability,
				modelResolver
		);
		KiroFormatConverter formatConverter = new KiroFormatConverter(streamConverter);

		facade = new KiroFacade(
				authManager,
				streamHandler,
				streamConverter,
				logPublisher,
				keyService,
				upstreamDispatcher,
				formatConverter
		);
	}

	@Test
	void handle_logsAccountThatServedRetry() throws Exception {
		StoredAccount acc1 = account("kiro1", "key1");
		StoredAccount acc2 = account("kiro2", "key2");
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("key1");
		when(authManager.getAccessToken(acc2)).thenReturn("key2");
		when(authManager.getDisplayName()).thenReturn("wrong-account");
		when(kiroClient.request(
				anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any(), anyBoolean()
		)).thenReturn(new KiroHttpClient.KiroResponse(
				429,
				new ByteArrayInputStream("rate limited".getBytes()),
				"application/json"
		));
		when(kiroClient.request(
				anyString(), anyString(), anyString(), anyBoolean(), eq("key2"), any(), anyBoolean()
		)).thenReturn(new KiroHttpClient.KiroResponse(
				200,
				new ByteArrayInputStream("data: {}\n".getBytes()),
				"text/event-stream"
		));

		facade.handle(request(), "claude-sonnet-4-20250514", false, 10,
				"key1", "127.0.0.1", Format.COMPLETION,
				new MockHttpServletResponse());

		ArgumentCaptor<RequestLogEvent> event = ArgumentCaptor.forClass(
				RequestLogEvent.class
		);
		verify(eventPublisher).publishEvent(event.capture());
		assertEquals("kiro2", event.getValue().accountId());
		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	@Test
	void handle_errorLogsAccountThatReturnedError() throws Exception {
		StoredAccount account = account("kiro1", "key1");
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(account));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("key1");
		when(authManager.getDisplayName()).thenReturn("wrong-account");
		when(kiroClient.request(
				anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any(), anyBoolean()
		)).thenReturn(new KiroHttpClient.KiroResponse(
				400,
				new ByteArrayInputStream("upstream error".getBytes()),
				"application/json"
		));

		facade.handle(request(), "claude-sonnet-4-20250514", false, 10,
				"key1", "127.0.0.1", Format.COMPLETION,
				new MockHttpServletResponse());

		ArgumentCaptor<RequestLogEvent> event = ArgumentCaptor.forClass(
				RequestLogEvent.class
		);
		verify(eventPublisher).publishEvent(event.capture());
		assertEquals("kiro1", event.getValue().accountId());
	}

	private StoredAccount account(String name, String token) {
		return StoredAccount.builder()
		                    .name(name)
		                    .provider("KIRO")
		                    .authType("API_KEY")
		                    .accessToken(token)
		                    .build();
	}

	private InternalRequest request() {
		return InternalRequest.builder()
		                      .model("claude-sonnet-4-20250514")
		                      .messages(List.of())
		                      .stream(false)
		                      .build();
	}
}
