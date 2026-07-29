package dev.suprim.gateway.proxy.token;

import dev.suprim.gateway.proxy.InternalRequest;
import lombok.Builder;

@Builder
public record OptimizationResult(
		InternalRequest request,
		OptimizationMetrics metrics
) {}
