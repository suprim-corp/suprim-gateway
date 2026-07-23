package dev.suprim.gateway.provider;

import dev.suprim.gateway.logging.LogTag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AccountRotator {

	private final CredentialStore credentialStore;
	private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

	public AccountRotator(CredentialStore credentialStore) {
		this.credentialStore = credentialStore;
	}

	@PostConstruct
	void logAccounts() {
		Map<String, List<StoredAccount>> byProvider =
				credentialStore.load()
			               .stream()
			               .filter(acc -> acc.provider() != null)
			               .collect(Collectors.groupingBy(StoredAccount::provider));
		for (Provider provider : Provider.values()) {
			List<StoredAccount> accounts = byProvider.getOrDefault(provider.name(), List.of());
			if (accounts.isEmpty()) {
				continue;
			}
			String tag = switch (provider) {
				case KIRO -> LogTag.KIRO;
				case XAI, GROK -> LogTag.XAI;
				case ANTIGRAVITY -> LogTag.ANTIGRAVITY;
				case CODEX -> LogTag.CODEX;
				case DEEPSEEK -> LogTag.DEEPSEEK;
			};
			String names = accounts.stream()
			                       .map(acc -> "\033[36m" + Optional.ofNullable(acc.name()).orElse("unnamed") + "\033[0m")
			                       .collect(Collectors.joining(", ", "[", "]"));
			log.info("{}Loaded {} accounts: {}", tag, accounts.size(), names);
		}
	}

	public StoredAccount next(String provider) {
		return next(provider, credentialStore.findAllByProvider(provider));
	}

	public StoredAccount next(String provider, List<StoredAccount> accounts) {
		if (accounts.isEmpty()) {
			throw new IllegalStateException("No accounts for provider: " + provider);
		}
		int index = counters.computeIfAbsent(provider, key -> new AtomicInteger(0)).getAndIncrement();
		return accounts.get(Math.floorMod(index, accounts.size()));
	}
}
