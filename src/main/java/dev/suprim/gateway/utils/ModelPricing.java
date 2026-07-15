package dev.suprim.gateway.utils;

import java.util.Arrays;

public enum ModelPricing {

    // Kiro
    KIRO_AUTO("auto", PricingTier.FLAT, 3, 15),
    KIRO_CLAUDE_SONNET_5("claude-sonnet-5", PricingTier.FLAT, 3, 15),
    KIRO_CLAUDE_OPUS_4_8("claude-opus-4-8", PricingTier.FLAT, 5, 25),
    KIRO_CLAUDE_OPUS_4_7("claude-opus-4-7", PricingTier.FLAT, 5, 25),
    KIRO_CLAUDE_OPUS_4_6("claude-opus-4-6", PricingTier.FLAT, 5, 25),
    KIRO_CLAUDE_SONNET_4_6("claude-sonnet-4-6", PricingTier.FLAT, 3, 15),
    KIRO_CLAUDE_SONNET_4("claude-sonnet-4", PricingTier.FLAT, 3, 15),
    KIRO_CLAUDE_SONNET_4_5("claude-sonnet-4.5", PricingTier.FLAT, 3, 15),
    KIRO_CLAUDE_OPUS_4("claude-opus-4", PricingTier.FLAT, 5, 25),
    KIRO_CLAUDE_OPUS_4_5("claude-opus-4.5", PricingTier.FLAT, 5, 25),
    KIRO_CLAUDE_HAIKU_4_5("claude-haiku-4.5", PricingTier.FLAT, 1, 5),
    KIRO_CLAUDE_HAIKU_4_5_ALT("claude-haiku-4-5", PricingTier.FLAT, 1, 5),
    KIRO_CLAUDE_3_7_SONNET("claude-3.7-sonnet", PricingTier.FLAT, 3, 15),
    KIRO_GPT_5_6_SOL("gpt-5.6-sol", PricingTier.FLAT, 5, 30),
    KIRO_GPT_5_6_TERRA("gpt-5.6-terra", PricingTier.FLAT, 2.5, 15),
    KIRO_GPT_5_6_LUNA("gpt-5.6-luna", PricingTier.FLAT, 1, 6),
    KIRO_DEEPSEEK_3_2("deepseek-3.2", PricingTier.FLAT, 0.62, 1.85),
    KIRO_MINIMAX_M2_5("minimax-m2.5", PricingTier.FLAT, 0.3, 1.2),
    KIRO_MINIMAX_M2_1("minimax-m2.1", PricingTier.FLAT, 0.3, 1.2),
    KIRO_GLM_5("glm-5", PricingTier.FLAT, 1, 3.2),
    KIRO_QWEN3_CODER_NEXT("qwen3-coder-next", PricingTier.FLAT, 0.5, 1.2),

    // Antigravity (Gemini)
    ANTIGRAVITY_GEMINI_3_1_PRO_HIGH("gemini-3.1-pro-high", PricingTier.CONTEXT_200K, 2, 4, 12, 18),
    ANTIGRAVITY_GEMINI_3_1_PRO_LOW("gemini-3.1-pro-low", PricingTier.CONTEXT_200K, 2, 4, 12, 18),
    ANTIGRAVITY_GEMINI_PRO_AGENT("gemini-pro-agent", PricingTier.CONTEXT_200K, 2, 4, 12, 18),
    ANTIGRAVITY_GEMINI_3_5_FLASH_LOW("gemini-3.5-flash-low", PricingTier.FLAT, 1.5, 9),
    ANTIGRAVITY_GEMINI_3_5_FLASH_EXTRA_LOW("gemini-3.5-flash-extra-low", PricingTier.FLAT, 1.5, 9),
    ANTIGRAVITY_GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite", PricingTier.FLAT, 0.25, 1.5),
    ANTIGRAVITY_GEMINI_3_FLASH("gemini-3-flash", PricingTier.FLAT, 0.5, 3),
    ANTIGRAVITY_GEMINI_3_FLASH_AGENT("gemini-3-flash-agent", PricingTier.FLAT, 0.5, 3),
    ANTIGRAVITY_GEMINI_2_5_PRO("gemini-2.5-pro", PricingTier.CONTEXT_200K, 1.25, 2.5, 10, 15),
    ANTIGRAVITY_GEMINI_2_5_FLASH("gemini-2.5-flash", PricingTier.FLAT, 0.3, 2.5),
    ANTIGRAVITY_GEMINI_2_5_FLASH_THINKING("gemini-2.5-flash-thinking", PricingTier.FLAT, 0.3, 2.5),
    ANTIGRAVITY_GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite", PricingTier.FLAT, 0.1, 0.4),
    ANTIGRAVITY_GEMINI_3_1_FLASH_IMAGE("gemini-3.1-flash-image", PricingTier.FLAT, 0.25, 1.5),
    ANTIGRAVITY_CLAUDE_SONNET_4_6("claude-sonnet-4-6", PricingTier.FLAT, 3, 15),
    ANTIGRAVITY_CLAUDE_OPUS_4_6("claude-opus-4-6-thinking", PricingTier.FLAT, 5, 25),

    // Grok (xAI)
    GROK_4_5("grok-4.5", PricingTier.CONTEXT_200K, 2, 4, 6, 12),
    GROK_4_3("grok-4.3", PricingTier.CONTEXT_200K, 1.25, 2.5, 2.5, 5),
    GROK_4_20_REASONING("grok-4.20-0309-reasoning", PricingTier.CONTEXT_200K, 1.25, 2.5, 2.5, 5),
    GROK_4_20_NON_REASONING("grok-4.20-0309-non-reasoning", PricingTier.CONTEXT_200K, 1.25, 2.5, 2.5, 5),
    GROK_4_20_MULTI_AGENT("grok-4.20-multi-agent-0309", PricingTier.CONTEXT_200K, 1.25, 2.5, 2.5, 5),
    GROK_BUILD("grok-build-0.1", PricingTier.CONTEXT_200K, 1, 2, 2, 4),

    // Codex (OpenAI)
    CODEX_GPT_5_6_SOL("gpt-5.6-sol", PricingTier.FLAT, 5, 30),
    CODEX_GPT_5_6_TERRA("gpt-5.6-terra", PricingTier.FLAT, 2.5, 15),
    CODEX_GPT_5_6_LUNA("gpt-5.6-luna", PricingTier.FLAT, 1, 6),
    CODEX_GPT_5_5("gpt-5.5", PricingTier.FLAT, 5, 30),
    CODEX_GPT_5_5_PRO("gpt-5.5-pro", PricingTier.FLAT, 30, 180),
    CODEX_GPT_5_4("gpt-5.4", PricingTier.FLAT, 2.5, 15),
    CODEX_GPT_5_4_PRO("gpt-5.4-pro", PricingTier.FLAT, 30, 180),
    CODEX_GPT_5_4_MINI("gpt-5.4-mini", PricingTier.FLAT, 0.75, 4.5),
    CODEX_GPT_5_4_NANO("gpt-5.4-nano", PricingTier.FLAT, 0.2, 1.25),
    CODEX_GPT_5_3_CODEX("gpt-5.3-codex", PricingTier.FLAT, 1.75, 14),
    CODEX_GPT_5_3_CODEX_SPARK("gpt-5.3-codex-spark", PricingTier.FLAT, 1.75, 14),
    CODEX_AUTO_REVIEW("codex-auto-review", PricingTier.FLAT, 2.5, 15);

    private final String modelId;
    private final PricingTier tier;
    private final double inputLow;
    private final double inputHigh;
    private final double outputLow;
    private final double outputHigh;

    ModelPricing(String modelId, PricingTier tier, double input, double output) {
        this(modelId, tier, input, input, output, output);
    }

    ModelPricing(String modelId, PricingTier tier, double inputLow, double inputHigh, double outputLow, double outputHigh) {
        this.modelId = modelId;
        this.tier = tier;
        this.inputLow = inputLow;
        this.inputHigh = inputHigh;
        this.outputLow = outputLow;
        this.outputHigh = outputHigh;
    }

    public double inputRate(int promptTokens) {
        return tier.isAboveThreshold(promptTokens) ? inputHigh : inputLow;
    }

    public double outputRate(int promptTokens) {
        return tier.isAboveThreshold(promptTokens) ? outputHigh : outputLow;
    }

    public static ModelPricing findByModelId(String modelId) {
        return Arrays.stream(values())
                     .filter(p -> p.modelId.equals(modelId))
                     .findFirst()
                     .orElse(null);
    }

    public enum PricingTier {
        FLAT(0),
        CONTEXT_200K(200_000);

        private final int threshold;

        PricingTier(int threshold) {
            this.threshold = threshold;
        }

        boolean isAboveThreshold(int tokenCount) {
            return threshold > 0 && tokenCount >= threshold;
        }
    }
}
