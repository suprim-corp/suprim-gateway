package dev.suprim.gateway.utils;

import org.springframework.stereotype.Component;

@Component
public class PricingService {

	public double calculateCost(
			String model,
			int promptTokens,
			int completionTokens
	) {
		ModelPricing pricing = ModelPricing.findByModelId(model);
		return (promptTokens * pricing.inputRate(promptTokens) +
		        completionTokens * pricing.outputRate(promptTokens)) /
		       1_000_000.0;
	}
}
