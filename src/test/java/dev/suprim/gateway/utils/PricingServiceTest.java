package dev.suprim.gateway.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingServiceTest {

    private final PricingService pricingService = new PricingService();

    @Test
    void calculateCost_usesClaudeFivePricing() {
        assertEquals(0.03, pricingService.calculateCost("claude-opus-5", 1_000, 1_000));
        assertEquals(0.012, pricingService.calculateCost("claude-sonnet-5", 1_000, 1_000));
        assertEquals(0.03, pricingService.calculateCost("claude-opus-4-8", 1_000, 1_000));
    }
}
