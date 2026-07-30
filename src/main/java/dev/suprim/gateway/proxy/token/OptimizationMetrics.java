package dev.suprim.gateway.proxy.token;

/** Request-local diagnostics; usage accounting remains owned by the provider response. */
public record OptimizationMetrics(
		int charactersBefore,
		int charactersAfter
) {}
