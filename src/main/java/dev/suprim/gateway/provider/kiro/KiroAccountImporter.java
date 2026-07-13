package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;

import dev.suprim.gateway.provider.kiro.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.provider.kiro.refresher.RefreshResult;
import dev.suprim.gateway.provider.kiro.refresher.SsoOidcTokenRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public final class KiroAccountImporter {

	private static final Logger log = LoggerFactory.getLogger(
			KiroAccountImporter.class);

	private KiroAccountImporter() {}

	public static ImportResult execute(
			ImportRequest req,
			CredentialStore credentialStore,
			HttpClient httpClient
	) throws Exception {
		String region = req.region() != null ? req.region() : "us-east-1";
		KiroCredentials.AuthType type = (req.clientId() != null &&
		                                 req.clientSecret() !=
		                                 null) ? KiroCredentials.AuthType.AWS_SSO_OIDC : KiroCredentials.AuthType.KIRO_DESKTOP;

		RefreshResult result;
		if (type == KiroCredentials.AuthType.AWS_SSO_OIDC) {
			result = SsoOidcTokenRefresher.refresh(
					req.refreshToken(),
					req.clientId(),
					req.clientSecret(),
					null,
					region,
					httpClient
			);
		} else {
			result = DesktopTokenRefresher.refresh(
					req.refreshToken(),
					region,
					httpClient
			);
		}

		String effectiveRefreshToken = result.refreshToken() !=
		                               null ? result.refreshToken() : req.refreshToken();
		String effectiveProfileArn = req.profileArn() !=
		                             null ? req.profileArn() : result.profileArn();

		if (effectiveProfileArn == null) {
			effectiveProfileArn = fetchProfileArn(
					result.accessToken(),
					region,
					httpClient
			);
		}

		StoredAccount account = StoredAccount.builder()
		                                     .profileArn(effectiveProfileArn)
		                                     .authType(type.name())
		                                     .clientId(req.clientId())
		                                     .clientSecret(req.clientSecret())
		                                     .accessToken(result.accessToken())
		                                     .refreshToken(effectiveRefreshToken)
		                                     .expiresAt(result.expiresAt())
		                                     .scopes(null)
		                                     .region(region)
		                                     .apiRegion(region)
		                                     .build();

		List<StoredAccount> before = credentialStore.load();
		boolean isNew = before.stream().noneMatch(a -> matchesKey(a, account));
		credentialStore.upsert(account);

		log.info(
				"[Kiro] Imported account: profileArn={}, clientId={}, type={}, isNew={}",
				effectiveProfileArn,
				req.clientId(),
				type,
				isNew
		);

		return ImportResult.builder()
		                   .profileArn(effectiveProfileArn)
		                   .authType(type.name())
		                   .isNew(isNew)
		                   .account(account)
		                   .build();
	}

	private static boolean matchesKey(
			StoredAccount existing,
			StoredAccount incoming
	) {
		if (incoming.profileArn() != null && existing.profileArn() != null) {
			return existing.profileArn().equals(incoming.profileArn());
		}
		if (incoming.clientId() != null && existing.clientId() != null) {
			return existing.clientId().equals(incoming.clientId());
		}
		return false;
	}

	private static String fetchProfileArn(
			String accessToken,
			String region,
			HttpClient httpClient
	) {
		try {
			String url = "https://codewhisperer." + region +
			             ".amazonaws.com/ListAvailableProfiles";
			HttpRequest request =
					HttpRequest.newBuilder()
					           .uri(URI.create(url))
					           .header(
							           "Content-Type",
							           "application/json"
					           )
					           .header(
							           "Authorization",
							           "Bearer " + accessToken
					           )
					           .header(
							           "Accept",
							           "application/json"
					           )
					           .POST(
							           HttpRequest.BodyPublishers.ofString(
									           "{\"maxResults\":10}")
					           )
					           .build();
			HttpResponse<String> response = httpClient.send(
					request,
					HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() != 200) {
				log.warn(
						"[Kiro] ListAvailableProfiles failed: {}",
						response.statusCode()
				);
				return null;
			}
			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(response.body());
			JsonNode profiles = root.get("profiles");
			if (profiles != null && profiles.isArray() && !profiles.isEmpty()) {
				JsonNode arn = profiles.get(0).get("arn");
				if (arn != null && !arn.isNull()) {
					return arn.asString();
				}
			}
		} catch (Exception e) {
			log.warn("[Kiro] Failed to fetch profileArn: {}", e.getMessage());
		}
		return null;
	}
}
