package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.payload.PayloadBuilder;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KiroUpstreamDispatcherTest {

	private KiroUpstreamDispatcher dispatcher;
	private KiroHttpClient kiroClient;
	private AccountRotator rotator;
	private CredentialStore store;
	private KiroAuthManager authManager;
	private KiroAccountModelAvailability modelAvailability;
	private ModelResolver modelResolver;
	private PayloadBuilder payloadBuilder;

	@BeforeEach
	void setUp() throws Exception {
		kiroClient = mock(KiroHttpClient.class);
		rotator = mock(AccountRotator.class);
		store = mock(CredentialStore.class);
		authManager = mock(KiroAuthManager.class);
		modelAvailability = mock(KiroAccountModelAvailability.class);
		modelResolver = new ModelResolver();
		payloadBuilder = mock(PayloadBuilder.class);

		dispatcher = new KiroUpstreamDispatcher(
				kiroClient, payloadBuilder, authManager, rotator, store, modelAvailability, modelResolver
		);

		when(modelAvailability.eligibleAccounts(anyString(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
		when(modelAvailability.isWarmUpComplete(anyList())).thenReturn(true);
		when(payloadBuilder.buildOpenAiPayload(any(), any())).thenReturn("{\"test\":true}");
	}

	@Test
	void dispatch_singleAccount_usesDirectPath() throws Exception {
		StoredAccount acc = StoredAccount.builder()
		                                  .name("solo").provider("KIRO")
		                                  .authType("API_KEY").accessToken("api-key-1")
		                                  .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc);
		when(authManager.getAccessToken(acc)).thenReturn("api-key-1");

		KiroResponse expected = new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-1"), anyBoolean()))
				.thenReturn(expected);

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();
		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				request, true
		);

		assertEquals(200, result.response().status());
		assertEquals("solo", result.accountId());
		verify(rotator).next(eq("KIRO"), anyList());
	}

	@Test
	void dispatch_preservesNonModel400BodyAfterInspection() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("solo").provider("KIRO")
		                                     .authType("API_KEY").accessToken("api-key-1")
		                                     .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(account));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("api-key-1");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(
						400,
						new ByteArrayInputStream("{\"message\":\"bad payload\"}".getBytes()),
						"application/json"
				));

		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-sonnet-4-20250514")
				               .messages(List.of())
				               .build(),
				true
		);

		assertEquals("{\"message\":\"bad payload\"}", new String(result.response().body().readAllBytes()));
	}

	@Test
	void dispatch_preservesRequestBodyInvalidResponseAfterDiagnostics() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("solo").provider("KIRO")
		                                     .authType("API_KEY").accessToken("api-key-1")
		                                     .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(account));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("api-key-1");
		when(payloadBuilder.buildOpenAiPayload(any(), any())).thenReturn(
				"{\"conversationState\":{\"currentMessage\":{\"userInputMessage\":" +
				"{\"content\":\"safe\",\"modelId\":\"claude-opus-5\",\"origin\":\"AI_EDITOR\"}}}}"
		);
		String error = "{\"message\":\"Improperly formed request.\",\"reason\":\"REQUEST_BODY_INVALID\"}";
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(
						400,
						new ByteArrayInputStream(error.getBytes()),
						"application/json"
				));

		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-opus-5")
				               .messages(List.of())
				               .build(),
				true
		);

		assertEquals(error, new String(result.response().body().readAllBytes()));
	}

	@Test
	void dispatch_withoutCachedModel_doesNotCallUpstream() throws Exception {
		StoredAccount acc = StoredAccount.builder()
		                                  .name("solo").provider("KIRO")
		                                  .authType("API_KEY").accessToken("api-key-1")
		                                  .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc));
		when(modelAvailability.eligibleAccounts(anyString(), anyList())).thenReturn(List.of());
		when(modelAvailability.isWarmUpComplete(anyList())).thenReturn(false);

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-5").messages(List.of()).build();

		RuntimeException error = assertThrows(RuntimeException.class, () -> dispatcher.dispatch(request, true));

		assertEquals("Kiro model availability is warming up", error.getMessage());
		verifyNoInteractions(kiroClient, payloadBuilder, authManager, rotator);
	}

	@Test
	void dispatch_multiAccount_retriesOn429() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-1")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-2")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("api-key-1");
		when(authManager.getAccessToken(acc2)).thenReturn("api-key-2");

		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(429, new ByteArrayInputStream("limited".getBytes()), "application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-2"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();
		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				request, true
		);

		assertEquals(200, result.response().status());
		assertEquals("k2", result.accountId());
		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	@Test
	void dispatch_multiAccount_retriesOnInvalidModelId() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-1")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-2")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("api-key-1");
		when(authManager.getAccessToken(acc2)).thenReturn("api-key-2");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(
						400,
						new ByteArrayInputStream("{\"reason\":\"INVALID_MODEL_ID\"}".getBytes()),
						"application/json"
				));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-2"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-5").messages(List.of()).build();
		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				request, true
		);

		assertEquals(200, result.response().status());
		assertEquals("k2", result.accountId());
		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	@Test
	void dispatch_allAccountsRejectModel_returnsInvalidModelResponse() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-1")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-2")
		                                   .build();
		String error = "{\"reason\":\"INVALID_MODEL_ID\"}";

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("api-key-1");
		when(authManager.getAccessToken(acc2)).thenReturn("api-key-2");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(400, new ByteArrayInputStream(error.getBytes()), "application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-2"), anyBoolean()))
				.thenReturn(new KiroResponse(400, new ByteArrayInputStream(error.getBytes()), "application/json"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-5").messages(List.of()).build();
		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(
				request, true
		);

		assertEquals(400, result.response().status());
		assertEquals("k2", result.accountId());
		assertEquals(error, new String(result.response().body().readAllBytes()));
		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	@Test
	void dispatch_allEndpoints403AfterRefresh_rotatesToNextAccount() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("KIRO_DESKTOP").accessToken("old-token")
		                                   .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("KIRO_DESKTOP").accessToken("second-token")
		                                   .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("old-token");
		when(authManager.forceRefresh(acc1)).thenReturn("refreshed-token");
		when(authManager.getAccessToken(acc2)).thenReturn("second-token");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("old-token"), anyBoolean()))
				.thenReturn(new KiroResponse(403, new ByteArrayInputStream(new byte[0]), "application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("refreshed-token"), anyBoolean()))
				.thenReturn(new KiroResponse(403, new ByteArrayInputStream(new byte[0]), "application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("second-token"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()), "text/event-stream"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();
		KiroUpstreamDispatcher.DispatchResult result = dispatcher.dispatch(request, true);

		assertEquals("k2", result.accountId());
		verify(authManager).forceRefresh(acc1);
		verify(authManager, never()).forceRefresh();
		verify(kiroClient, times(3)).request(anyString(), anyString(), anyString(), anyBoolean(), eq("old-token"), anyBoolean());
		verify(kiroClient, times(3)).request(anyString(), anyString(), anyString(), anyBoolean(), eq("refreshed-token"), anyBoolean());
		ArgumentCaptor<List<StoredAccount>> candidates = ArgumentCaptor.forClass(List.class);
		verify(rotator, times(2)).next(eq("KIRO"), candidates.capture());
		assertEquals(List.of(acc1, acc2), candidates.getAllValues().get(0));
		assertEquals(List.of(acc2), candidates.getAllValues().get(1));
	}

	@Test
	void dispatch_duplicateAccounts_attemptsEachUniqueAccountOnlyOnce() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("KIRO_DESKTOP").accessToken("first-token")
		                                   .build();
		StoredAccount duplicate = StoredAccount.builder()
		                                        .name("k1").provider("KIRO")
		                                        .authType("KIRO_DESKTOP").accessToken("first-token")
		                                        .build();
		StoredAccount acc2 = StoredAccount.builder()
		                                   .name("k2").provider("KIRO")
		                                   .authType("KIRO_DESKTOP").accessToken("second-token")
		                                   .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, duplicate, acc2));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1, acc2);
		when(authManager.getAccessToken(acc1)).thenReturn("first-token");
		when(authManager.getAccessToken(acc2)).thenReturn("second-token");
		when(authManager.forceRefresh(acc1)).thenReturn("first-token-refreshed");
		when(authManager.forceRefresh(acc2)).thenReturn("second-token-refreshed");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean()))
				.thenReturn(new KiroResponse(403, new ByteArrayInputStream(new byte[0]), "application/json"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();

		assertThrows(RuntimeException.class, () -> dispatcher.dispatch(request, true));
		verify(authManager).forceRefresh(acc1);
		verify(authManager).forceRefresh(acc2);
		verify(authManager, times(2)).forceRefresh(any(StoredAccount.class));
		verify(rotator, times(2)).next(eq("KIRO"), anyList());
	}

	/**
	 * The payload must carry the ARN of the account whose token is being sent. Sending the
	 * connected account's ARN instead makes the upstream reject the token outright, which is what
	 * happens when the payload is built once outside the rotation loop.
	 */
	@Test
	void dispatch_buildsPayloadWithTheSendingAccountsProfileArn() throws Exception {
		StoredAccount acc = StoredAccount.builder()
		                                 .name("sso").provider("KIRO")
		                                 .authType("AWS_SSO_OIDC")
		                                 .accessToken("sso-token")
		                                 .profileArn("arn:aws:codewhisperer:us-east-1:111:profile/OWN")
		                                 .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc);
		when(authManager.getAccessToken(acc)).thenReturn("sso-token");
		when(authManager.getProfileArn()).thenReturn(
				"arn:aws:codewhisperer:us-east-1:999:profile/CONNECTED"
		);
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("sso-token"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()),
						"text/event-stream"));

		dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-sonnet-4-20250514")
				               .messages(List.of())
				               .build(),
				true
		);

		ArgumentCaptor<String> arn = ArgumentCaptor.forClass(String.class);
		verify(payloadBuilder).buildOpenAiPayload(any(), arn.capture());
		assertEquals(
				"arn:aws:codewhisperer:us-east-1:111:profile/OWN",
				arn.getValue()
		);
	}

	/**
	 * Each account in a rotation sends its own ARN, so the payload cannot be built once and reused
	 * across accounts.
	 */
	@Test
	void dispatch_rotation_sendsEachAccountsOwnProfileArn() throws Exception {
		StoredAccount first = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("AWS_SSO_OIDC")
		                                   .accessToken("token-1")
		                                   .profileArn("arn:aws:codewhisperer:us-east-1:111:profile/ONE")
		                                   .build();
		StoredAccount second = StoredAccount.builder()
		                                    .name("k2").provider("KIRO")
		                                    .authType("AWS_SSO_OIDC")
		                                    .accessToken("token-2")
		                                    .profileArn("arn:aws:codewhisperer:us-east-1:222:profile/TWO")
		                                    .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(first, second));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(first, second);
		when(authManager.getAccessToken(first)).thenReturn("token-1");
		when(authManager.getAccessToken(second)).thenReturn("token-2");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("token-1"), anyBoolean()))
				.thenReturn(new KiroResponse(429, new ByteArrayInputStream("limited".getBytes()),
						"application/json"));
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("token-2"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()),
						"text/event-stream"));

		dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-sonnet-4-20250514")
				               .messages(List.of())
				               .build(),
				true
		);

		ArgumentCaptor<String> arns = ArgumentCaptor.forClass(String.class);
		verify(payloadBuilder, times(2)).buildOpenAiPayload(any(), arns.capture());
		assertEquals(
				List.of(
						"arn:aws:codewhisperer:us-east-1:111:profile/ONE",
						"arn:aws:codewhisperer:us-east-1:222:profile/TWO"
				),
				arns.getAllValues()
		);
	}

	/** An API key is already scoped by the key, so it must not send a profile ARN. */
	@Test
	void dispatch_apiKeyAccount_sendsNoProfileArn() throws Exception {
		StoredAccount acc = StoredAccount.builder()
		                                 .name("key").provider("KIRO")
		                                 .authType("api_key").accessToken("api-key-1")
		                                 .profileArn("arn:aws:codewhisperer:us-east-1:111:profile/IGNORED")
		                                 .build();
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc);
		when(authManager.getAccessToken(acc)).thenReturn("api-key-1");
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(200, new ByteArrayInputStream("ok".getBytes()),
						"text/event-stream"));

		dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-sonnet-4-20250514")
				               .messages(List.of())
				               .build(),
				true
		);

		ArgumentCaptor<String> arn = ArgumentCaptor.forClass(String.class);
		verify(payloadBuilder).buildOpenAiPayload(any(), arn.capture());
		assertNull(arn.getValue());
	}

	@Test
	void dispatch_allAccountsExhausted_throws() throws Exception {
		StoredAccount acc1 = StoredAccount.builder()
		                                   .name("k1").provider("KIRO")
		                                   .authType("API_KEY").accessToken("api-key-1")
		                                   .build();

		when(store.findAllByProvider("KIRO")).thenReturn(List.of(acc1, acc1));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(acc1);
		when(authManager.getAccessToken(acc1)).thenReturn("api-key-1");

		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(), eq("api-key-1"), anyBoolean()))
				.thenReturn(new KiroResponse(429, new ByteArrayInputStream("limited".getBytes()), "application/json"));

		InternalRequest request = InternalRequest.builder().model("claude-sonnet-4-20250514").messages(List.of()).build();

		assertThrows(RuntimeException.class, () -> dispatcher.dispatch(request, true));
	}

	/**
	 * An AWS SSO token is refused by the kiro.dev gateway and gets REQUEST_BODY_INVALID from
	 * CodeWhisperer, so the Q surface goes first and kiro.dev is the last resort.
	 */
	@Test
	void dispatch_awsSsoAccount_triesQSurfaceFirstAndRuntimeLast() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("sso").provider("KIRO")
		                                     .authType("AWS_SSO_OIDC")
		                                     .accessToken("sso-token")
		                                     .build();

		assertEquals(
				List.of(
						"https://q.us-east-1.amazonaws.com/generateAssistantResponse",
						"https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
						"https://runtime.us-east-1.kiro.dev/generateAssistantResponse"
				),
				endpointsTried(account)
		);
	}

	/** A Kiro OIDC or social login keeps the kiro.dev gateway first: it accepts that token. */
	@Test
	void dispatch_oauthAccount_triesRuntimeFirst() throws Exception {
		StoredAccount account = StoredAccount.builder()
		                                     .name("desktop").provider("KIRO")
		                                     .authType("KIRO_DESKTOP")
		                                     .accessToken("desktop-token")
		                                     .build();

		assertEquals(
				List.of(
						"https://runtime.us-east-1.kiro.dev/generateAssistantResponse",
						"https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
						"https://q.us-east-1.amazonaws.com/generateAssistantResponse"
				),
				endpointsTried(account)
		);
	}

	/**
	 * The endpoint URLs an account is tried against, in order. Every surface answers 403 so the
	 * dispatcher walks the whole list instead of stopping at the first one.
	 */
	private List<String> endpointsTried(StoredAccount account) throws Exception {
		when(store.findAllByProvider("KIRO")).thenReturn(List.of(account));
		when(rotator.next(eq("KIRO"), anyList())).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn(account.accessToken());
		when(authManager.forceRefresh(account)).thenReturn(account.accessToken());
		when(kiroClient.request(anyString(), anyString(), anyString(), anyBoolean(),
				any(), anyBoolean()))
				.thenAnswer(invocation -> new KiroResponse(
						403,
						new ByteArrayInputStream("denied".getBytes()),
						"application/json"
				));

		assertThrows(RuntimeException.class, () -> dispatcher.dispatch(
				InternalRequest.builder()
				               .model("claude-sonnet-4-20250514")
				               .messages(List.of())
				               .build(),
				true
		));

		ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
		verify(kiroClient, atLeast(3)).request(anyString(), urls.capture(),
				anyString(), anyBoolean(), any(), anyBoolean());
		return urls.getAllValues().subList(0, 3);
	}
}
