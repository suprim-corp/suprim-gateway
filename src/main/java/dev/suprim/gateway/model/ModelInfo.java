package dev.suprim.gateway.model;

import lombok.Builder;

@Builder
public record ModelInfo(String id, Integer quota, Double cost, String unit) {

	public static ModelInfo of(String id) {
		return new ModelInfo(id, null, null, null);
	}

	public static ModelInfo of(String id, int quota) {
		return new ModelInfo(id, quota, null, null);
	}

	public static ModelInfo of(String id, double cost, String unit) {
		return new ModelInfo(id, null, cost, unit);
	}
}
