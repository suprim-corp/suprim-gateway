package dev.suprim.gateway.provider;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.provider.antigravity.GoogleTokenRefresher;
import dev.suprim.gateway.provider.antigravity.GoogleTokenResponse;
import dev.suprim.gateway.provider.codex.CodexTokenRefresher;
import dev.suprim.gateway.provider.codex.CodexTokenResponse;
import dev.suprim.gateway.provider.kiro.refresher.DesktopTokenRefresher;
import dev.suprim.gateway.provider.kiro.refresher.RefreshResult;
import dev.suprim.gateway.provider.kiro.refresher.SsoOidcTokenRefresher;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import dev.suprim.gateway.provider.xai.XaiTokenRefresher;
import dev.suprim.gateway.provider.xai.XaiTokenResponse;
import dev.suprim.gateway.proxy.ProxyChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class TokenRefreshScheduler {

	private static final String GREEN = "\033[32m";
	private static final String RED = "\033[31m";
	private static final String RESET = "\033[0m";

	private final CredentialStore credentialStore;
	private final ProxyChain proxyChain;
	private final XaiAuthManager xaiAuthManager;

	@Scheduled(fixedDelay = 1_800_000)
	void refreshAll() {
		List<StoredAccount> accounts = credentialStore.load();
		Instant threshold = Instant.now().plusSeconds(1800);

		for (StoredAccount account : accounts) {
			if (account.refreshToken() == null) continue;
			if (account.expiresAt() != null && account.expiresAt().isAfter(threshold)) continue;

			StoredAccount refreshed = refreshAccount(account);
			if (refreshed != null) {
				credentialStore.upsert(refreshed);
				if (Provider.XAI.name().equals(account.provider())) {
					xaiAuthManager.evictTokenCache(account.name());
				}
				log.info("⟳ {}[{}]{} Refreshed: {}", GREEN, account.provider(), RESET, account.name());
			}
		}
	}

	private StoredAccount refreshAccount(StoredAccount account) {
		try {
			String refreshToken = account.refreshToken();
			String newAccess;
			String newRefresh;
			Instant newExpires;

			switch (Provider.valueOf(account.provider())) {
				case ANTIGRAVITY -> {
					GoogleTokenResponse r = GoogleTokenRefresher.refresh(
							refreshToken, Antigravity.CLIENT_ID, Antigravity.CLIENT_SECRET
					);
					newAccess = r.accessToken();
					newRefresh = r.refreshToken() != null ? r.refreshToken() : refreshToken;
					newExpires = Instant.now().plusSeconds(r.expiresIn());
				}
				case XAI -> {
					XaiTokenResponse r = XaiTokenRefresher.refresh(refreshToken);
					newAccess = r.accessToken();
					newRefresh = r.refreshToken() != null ? r.refreshToken() : refreshToken;
					newExpires = Instant.now().plusSeconds(r.expiresIn());
				}
				case CODEX -> {
					CodexTokenResponse r = CodexTokenRefresher.refresh(refreshToken);
					newAccess = r.accessToken();
					newRefresh = r.refreshToken() != null ? r.refreshToken() : refreshToken;
					newExpires = Instant.now().plusSeconds(r.expiresIn());
				}
				case KIRO -> {
					String authType = account.authType();
					String region = account.region();
					RefreshResult r;
					if ("KIRO_DESKTOP".equals(authType)) {
						r = DesktopTokenRefresher.refresh(refreshToken, region, proxyChain.currentClient());
					} else {
						r = SsoOidcTokenRefresher.refresh(
								refreshToken, account.clientId(), account.clientSecret(),
								account.scopes(), region, proxyChain.currentClient()
						);
					}
					newAccess = r.accessToken();
					newRefresh = r.refreshToken() != null ? r.refreshToken() : refreshToken;
					newExpires = r.expiresAt();
				}
				default -> {
					return null;
				}
			}
			return account.withTokens(newAccess, newRefresh, newExpires);
		} catch (Exception e) {
			log.warn("⟳ {}[{}]{} Refresh failed for {}: {}", RED, account.provider(), RESET, account.name(), e.getMessage());
			return null;
		}
	}
}
