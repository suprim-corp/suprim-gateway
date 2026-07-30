package dev.suprim.gateway.proxy.token;

import dev.suprim.gateway.proxy.InternalRequest;

public record OptimizationResult(
		InternalRequest request,
		OptimizationMetrics metrics
) {}
