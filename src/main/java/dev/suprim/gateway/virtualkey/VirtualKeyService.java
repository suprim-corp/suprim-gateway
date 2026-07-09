package dev.suprim.gateway.virtualkey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class VirtualKeyService {

	private final VirtualKeyRepository repository;

	public VirtualKey resolveByRawKey(String rawKey) {
		if (rawKey == null || !rawKey.startsWith("sk-")) return null;
		String hash = VirtualKeyRepository.hashKey(rawKey);
		return repository.findByKeyHash(hash);
	}

	public record CreateKeyResult(VirtualKey key, String rawKey) {}

	public CreateKeyResult create(String name, int rateLimitPerMin) {
		String rawKey = "sk-" + generateRandomString(32);
		String hash = VirtualKeyRepository.hashKey(rawKey);
		String prefix = rawKey.substring(0, 11);
		String id = UUID.randomUUID().toString();
		VirtualKey key = new VirtualKey(
				id, name, hash, prefix, null, true, null,
				rateLimitPerMin, null, null, null, null, null,
				0, 0, null, 0, 0, null, System.currentTimeMillis()
		);
		repository.insert(key);
		return new CreateKeyResult(key, rawKey);
	}

	public List<VirtualKey> list(int limit, int offset) {
		return repository.findAll(limit, offset);
	}

	public int count() {
		return repository.count();
	}

	public void toggle(String id) {
		VirtualKey key = repository.findById(id);
		if (key != null) repository.updateEnabled(id, !key.enabled());
	}

	public void revoke(String id) {
		repository.revoke(id);
	}

	public void updateLimits(
			String id,
			int rateLimitPerMin,
			String budgetPeriod,
			Integer budgetTokens,
			Integer budgetRequests,
			Integer budgetCost
	) {
		repository.updateLimits(
				id,
				rateLimitPerMin,
				budgetPeriod,
				budgetTokens,
				budgetRequests,
				budgetCost
		);
	}

	public void incrementUsage(String keyId, int tokens) {
		repository.incrementUsage(keyId, tokens);
	}

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	private String generateRandomString(int length) {
		SecureRandom random = new SecureRandom();
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}
}
