package dev.suprim.gateway.model;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ModelResolver {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("claude-sonnet-4-5", "claude-sonnet-4-5"),
            Map.entry("claude-sonnet-4.5", "claude-sonnet-4-5"),
            Map.entry("claude-haiku-4-5", "claude-haiku-4-5"),
            Map.entry("claude-haiku-4.5", "claude-haiku-4-5"),
            Map.entry("claude-sonnet-4", "claude-sonnet-4"),
            Map.entry("claude-opus-4", "claude-opus-4"),
            Map.entry("gpt-4o", "claude-sonnet-4-5"),
            Map.entry("gpt-4", "claude-sonnet-4-5")
    );

    public String resolve(String model) {
        if (model == null) return "claude-sonnet-4-5";
        String normalized = normalize(model);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    private String normalize(String model) {
        String stripped = model.replaceAll("-\\d{8}$", "");
        stripped = stripped.replaceAll("\\[\\d+[km]\\]$", "");
        stripped = stripped.replace(".", "-");
        return stripped.toLowerCase();
    }
}
