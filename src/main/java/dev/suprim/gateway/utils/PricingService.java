package dev.suprim.gateway.utils;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PricingService {

    private record ModelPricing(double input, double output) {}

    private static final Map<String, ModelPricing> PRICING = Map.ofEntries(
            Map.entry("auto", new ModelPricing(3, 15)),
            Map.entry("claude-sonnet-4", new ModelPricing(3, 15)),
            Map.entry("claude-sonnet-4.5", new ModelPricing(3, 15)),
            Map.entry("claude-sonnet-4.6", new ModelPricing(3, 15)),
            Map.entry("claude-opus-4", new ModelPricing(5, 25)),
            Map.entry("claude-opus-4.5", new ModelPricing(5, 25)),
            Map.entry("claude-opus-4.6", new ModelPricing(5, 25)),
            Map.entry("claude-haiku-4.5", new ModelPricing(1, 5)),
            Map.entry("claude-3.7-sonnet", new ModelPricing(3, 15)),
            Map.entry("deepseek-v3.2", new ModelPricing(0.62, 1.85)),
            Map.entry("deepseek-3.2", new ModelPricing(0.62, 1.85)),
            Map.entry("glm-5", new ModelPricing(1, 3.2)),
            Map.entry("minimax-m2.5", new ModelPricing(0.3, 1.2)),
            Map.entry("minimax-m2.1", new ModelPricing(0.3, 1.2)),
            Map.entry("qwen3-coder-next", new ModelPricing(0.15, 1.2))
    );

    private static final ModelPricing DEFAULT_PRICING = new ModelPricing(3, 15);

    public double calculateCost(String model, int promptTokens, int completionTokens) {
        ModelPricing pricing = PRICING.getOrDefault(model, DEFAULT_PRICING);
        return (promptTokens * pricing.input + completionTokens * pricing.output) / 1_000_000.0;
    }
}
