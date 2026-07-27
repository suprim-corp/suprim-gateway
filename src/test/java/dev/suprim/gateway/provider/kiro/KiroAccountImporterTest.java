package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KiroAccountImporterTest {

	private static final String REFRESH_BODY = """
			{"accessToken":"access-1","refreshToken":"refresh-2",
			 "expiresAt":"2030-01-01T00:00:00Z"}
			""";

	@TempDir
	Path tempDir;

	private CredentialStore store;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		store = new CredentialStore(tempDir.resolve("creds.json"));
		httpClient = mock(HttpClient.class);
	}

	/**
	 * Routes the two upstream calls the importer makes: the token refresh and
	 * the ListAvailableProfiles lookup.
	 */
	private void stubUpstream(String profilesBody) throws Exception {
		when(httpClient.send(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			String path = request.uri().getPath();
			return "/refreshToken".equals(path)
					? jsonResponse(200, REFRESH_BODY)
					: jsonResponse(200, profilesBody);
		});
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> jsonResponse(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		return response;
	}

	private ImportRequest desktopRequest(String region) {
		return ImportRequest.builder()
		                    .refreshToken("refresh-1")
		                    .region(region)
		                    .build();
	}

	private List<String> requestedHosts() throws Exception {
		ArgumentCaptor<HttpRequest> captor =
				ArgumentCaptor.forClass(HttpRequest.class);
		verify(httpClient, atLeastOnce()).send(captor.capture(), any());
		List<String> hosts = new ArrayList<>();
		for (HttpRequest request : captor.getAllValues()) {
			hosts.add(request.uri().getHost());
		}
		return hosts;
	}

	@Test
	void execute_storesProviderSoAccountIsFoundByProviderLookup()
			throws Exception {
		stubUpstream("""
				{"profiles":[{"arn":"arn:aws:codewhisperer:us-east-1:1:profile/P"}]}
				""");

		KiroAccountImporter.execute(
				desktopRequest("us-east-1"),
				store,
				httpClient
		);

		List<StoredAccount> kiroAccounts =
				store.findAllByProvider(Provider.KIRO.name());
		assertEquals(1, kiroAccounts.size());
		assertEquals(
				"arn:aws:codewhisperer:us-east-1:1:profile/P",
				kiroAccounts.getFirst().profileArn()
		);
	}

	@Test
	void execute_fetchesProfileArnFromUsEast1ForOtherRegions()
			throws Exception {
		stubUpstream("""
				{"profiles":[{"arn":"arn:aws:codewhisperer:us-east-2:1:profile/Q"}]}
				""");

		ImportResult result = KiroAccountImporter.execute(
				desktopRequest("us-east-2"),
				store,
				httpClient
		);

		assertTrue(
				requestedHosts().contains("codewhisperer.us-east-1.amazonaws.com"),
				"profileArn lookup must target us-east-1, got " + requestedHosts()
		);
		assertEquals(
				"arn:aws:codewhisperer:us-east-2:1:profile/Q",
				result.profileArn()
		);
	}

	@Test
	void execute_keepsRequestedRegionOnTheAccount() throws Exception {
		stubUpstream("{\"profiles\":[]}");

		ImportResult result = KiroAccountImporter.execute(
				desktopRequest("us-east-2"),
				store,
				httpClient
		);

		assertEquals("us-east-2", result.account().region());
		assertEquals("us-east-2", result.account().apiRegion());
		assertEquals(Provider.KIRO.name(), result.account().provider());
		assertNull(result.profileArn());
	}
}
