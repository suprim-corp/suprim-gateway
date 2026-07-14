package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KiroUpstreamDispatcherTest {

	private KiroUpstreamDispatcher dispatcher;
	private KiroHttpClient kiroClient;
	private AccountRotator rotator;
	private CredentialStore store;
	private KiroAuthManager authManager;
	private PayloadBuilder payloadBuilder;

	@BeforeEach
	void setUp() throws Exception {
		kiroClient = mock(KiroHttpClient.class);
		rotator = mock(AccountRotator.class);
		store = mock(CredentialStore.class);
		authManager = mock(KiroAuthManager.class);
		payloadBuilder = mock(PayloadBuilder.class);

		dispatcher = new KiroUpstreamDispatcher(kiroClient, payloadBuilder, authManager, rotator, store);

		when(payloadBuilder.buildOpenAiPayload(any(), any())).thenReturn("{\"test\":true}");
	}

	@Test
	void dispatch_singleAccount_usesDirectPath() throws Exception {
		StoredAccount acc = StoredAccount.builder()
		                                  .name("solo").provider("KIRO")
		                                  .authType("API_KEY").accessToken("key1")
		                                  .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc));
		when(authManager.getAccessToken()).thenReturn("key1");

		KiroResponse expected = new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any()))
				.thenReturn(expected);

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();
		KiroResponse result = dispatcher.dispatch(request, true);

		assertEquals(200, result.status());
		verify(rotator, never()).next(anyString());
	}

	@Test
	void dispatch_multiAccount_retriesOn429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key1")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key2")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next("KIRO")).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("key1");
		when(authManager.getAccessToken(acc2)).thenReturn("key2");

		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any()))
				.thenReturn(new KiroResponse(429, new ByteArrayInputStream("limited".getBytes()), "application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key2"), any()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();
		KiroResponse result = dispatcher.dispatch(request, true);

		assertEquals(200, result.status());
		verify(rotator, times(2)).next("KIRO");
	}

	@Test
	void dispatch_allAccountsExhausted_throws() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("key1")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc1));
		when(rotator.next("KIRO")).thenReturn(acc1);
		when(authManager.getAccessToken(acc1)).thenReturn("key1");

		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("key1"), any()))
				.thenReturn(new KiroResponse(429, new ByteArrayInputStream("limited".getBytes()), "application/json"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();

		assertThrows(RuntimeException.class, () -> dispatcher.dispatch(request, true));
	}
}
