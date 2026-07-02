package dev.suprim.gateway.virtualkey;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class RateLimiter {

    private final Map<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    public boolean isAllowed(String keyId, int limitPerMin) {
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> window = windows.computeIfAbsent(keyId, k -> new ConcurrentLinkedDeque<>());

        while (!window.isEmpty() && window.peekFirst() < now - 60_000) {
            window.pollFirst();
        }

        if (window.size() >= limitPerMin) return false;

        window.addLast(now);
        return true;
    }

    void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Long> window = entry.getValue();
            while (!window.isEmpty() && window.peekFirst() < now - 60_000) {
                window.pollFirst();
            }
            return window.isEmpty();
        });
    }
}
