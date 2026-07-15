package dev.suprim.gateway.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PricingService {

	public double calculateCost(
			String model,
			int promptTokens,
			int completionTokens
	) {
		ModelPricing pricing = ModelPricing.findByModelId(model);
		if (pricing == null) {
			log.warn("\033[33m[Pricing]\033[0m No pricing found for model: {}", model);
			return 0;
		}
		return (promptTokens * pricing.inputRate(promptTokens) +
		        completionTokens * pricing.outputRate(promptTokens)) /
		       1_000_000.0;
	}
}
