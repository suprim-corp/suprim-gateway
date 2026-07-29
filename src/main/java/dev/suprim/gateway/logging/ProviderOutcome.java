package dev.suprim.gateway.logging;

import lombok.Builder;

@Builder
public record ProviderOutcome(RequestLogEvent event) {

	private static final ProviderOutcome NONE = ProviderOutcome.builder().build();

	public static ProviderOutcome none() {
		return NONE;
	}

	public static ProviderOutcome logged(RequestLogEvent event) {
		return ProviderOutcome.builder().event(event).build();
	}
}
