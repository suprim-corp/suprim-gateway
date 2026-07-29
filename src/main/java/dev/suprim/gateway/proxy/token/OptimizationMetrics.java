package dev.suprim.gateway.proxy.token;

import lombok.Builder;

/** Request-local diagnostics; usage accounting remains owned by the provider response. */
@Builder
public record OptimizationMetrics(
		int charactersBefore,
		int charactersAfter
) {}
