package dev.suprim.gateway.provider.kiro.sso;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.kiro.ImportRequest;
import dev.suprim.gateway.provider.kiro.ImportResult;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class KiroSsoFacade {

	private final KiroAuthManager authManager;
	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final ConcurrentHashMap<String, SsoSession> sessions = new ConcurrentHashMap<>();
	private final Set<String> polling = ConcurrentHashMap.newKeySet();

	public Map<String, Object> startDeviceFlow(String startUrl, String region) {
		if (startUrl == null || startUrl.isBlank()) {
			return Map.of("error", "startUrl is required");
		}

		try {
			Map<String, String> reg = SsoDeviceFlowService.registerClient(
					startUrl,
					region,
					proxyChain
			);
			String clientId = reg.get("clientId");
			String clientSecret = reg.get("clientSecret");

			Map<String, Object> device = SsoDeviceFlowService.startDeviceAuthorization(
					clientId, clientSecret, startUrl, region, proxyChain
			);

			String sessionId = UUID.randomUUID().toString();
			int interval = (int) device.get("interval");
			int expiresIn = (int) device.get("expiresIn");

			SsoSession session =
					SsoSession.builder()
					          .sessionId(sessionId)
					          .clientId(clientId)
					          .clientSecret(clientSecret)
					          .deviceCode(
							          (String) device.get("deviceCode")
					          )
					          .interval(interval)
					          .expiresAt(
							          Instant.now()
							                 .plusSeconds(expiresIn)
					          )
					          .startUrl(startUrl)
					          .region(region)
					          .build();
			sessions.put(sessionId, session);

			log.info("[Kiro SSO] Device auth started, session={}", sessionId);

			return Map.of(
					"sessionId",
					sessionId,
					"verificationUri",
					device.get("verificationUri"),
					"verificationUriComplete",
					device.getOrDefault("verificationUriComplete", ""),
					"userCode",
					device.get("userCode"),
					"interval",
					interval,
					"expiresIn",
					expiresIn
			);
		} catch (Exception e) {
			log.error("[Kiro SSO] Start failed: {}", e.getMessage());
			return Map.of("error", e.getMessage());
		}
	}

	public Map<String, Object> pollToken(String sessionId) {
		SsoSession ssoSession = sessions.get(sessionId);
		if (ssoSession == null) {
			return Map.of("status", "error", "message", "Invalid session");
		}

		if (Instant.now().isAfter(ssoSession.expiresAt())) {
			sessions.remove(sessionId);
			return Map.of("status", "expired", "message", "Session expired");
		}

		if (!polling.add(sessionId)) {
			return Map.of("status", "pending");
		}

		try {
			Map<String, Object> result = SsoDeviceFlowService.createToken(
					ssoSession.clientId(), ssoSession.clientSecret(),
					ssoSession.deviceCode(), ssoSession.region(), proxyChain
			);

			String status = (String) result.get("status");
			if ("pending".equals(status) || "slow_down".equals(status)) {
				return Map.of("status", "pending");
			}
			if ("expired".equals(status) || "denied".equals(status)) {
				sessions.remove(sessionId);
				return Map.of(
						"status",
						"expired",
						"message",
						"Device code expired or denied"
				);
			}

			ImportRequest importReq =
					ImportRequest.builder()
					             .refreshToken(
							             (String) result.get("refreshToken")
					             )
					             .clientId(ssoSession.clientId())
					             .clientSecret(ssoSession.clientSecret())
					             .region(ssoSession.region())
					             .build();
			ImportResult importResult = authManager.importAccount(importReq);
			StoredAccount imported = importResult.account();
			String accountName = SsoDeviceFlowService.fetchEmail(
					imported.accessToken(),
					ssoSession.region(),
					proxyChain
			);

			StoredAccount withMeta = StoredAccount.builder()
			                                      .name(accountName)
			                                      .profileArn(imported.profileArn())
			                                      .authType(imported.authType())
			                                      .clientId(imported.clientId())
			                                      .clientSecret(imported.clientSecret())
			                                      .accessToken(imported.accessToken())
			                                      .refreshToken(imported.refreshToken())
			                                      .expiresAt(imported.expiresAt())
			                                      .scopes(imported.scopes())
			                                      .region(imported.region())
			                                      .apiRegion(imported.apiRegion())
			                                      .provider(Provider.KIRO.name())
			                                      .projectId(imported.projectId())
			                                      .build();
			credentialStore.upsert(withMeta);
			sessions.remove(sessionId);

			log.info(
					"[Kiro SSO] Login complete, profileArn={}",
					importResult.profileArn()
			);
			return Map.of(
					"status",
					"ok",
					"profileArn",
					String.valueOf(importResult.profileArn())
			);

		} catch (Exception e) {
			log.error("[Kiro SSO] Poll error: {}", e.getMessage());
			return Map.of("status", "error", "message", e.getMessage());
		} finally {
			polling.remove(sessionId);
		}
	}
}
