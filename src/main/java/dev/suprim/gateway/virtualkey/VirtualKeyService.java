package dev.suprim.gateway.virtualkey;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class VirtualKeyService {

    private final VirtualKeyRepository repository;

    VirtualKeyService(VirtualKeyRepository repository) {
        this.repository = repository;
    }

    public VirtualKey resolveByRawKey(String rawKey) {
        if (rawKey == null || !rawKey.startsWith("sk-kiro-")) return null;
        String hash = VirtualKeyRepository.hashKey(rawKey);
        return repository.findByKeyHash(hash);
    }

    public record CreateKeyResult(VirtualKey key, String rawKey) {}

    public CreateKeyResult create(String name, int rateLimitPerMin) {
        String rawKey = "sk-kiro-" + generateRandomString(32);
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

    void updateBudget(String id, String period, Integer tokens, Integer requests, Integer cost) {
        repository.updateBudget(id, period, tokens, requests, cost);
    }

    public void incrementUsage(String keyId, int tokens) {
        repository.incrementUsage(keyId, tokens);
    }

    private String generateRandomString(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }
}
