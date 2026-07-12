package dev.suprim.gateway.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class TokenRefreshScheduler {

	private final List<ProviderAuthManager> providers;

	@Scheduled(fixedDelay = 2_700_000)
	void refreshAll() {
		for (ProviderAuthManager provider : providers) {
			if (!provider.isConnected()) {
				continue;
			}

			try {
				provider.getAccessToken();
				log.info(
						"[{}] Scheduled refresh OK",
						provider.getProviderName()
				);
			} catch (Exception e) {
				log.warn(
						"[{}] Scheduled refresh failed: {}",
						provider.getProviderName(),
						e.getMessage()
				);
			}
		}
	}
}
