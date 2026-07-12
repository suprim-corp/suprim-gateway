package dev.suprim.gateway.model;

import lombok.Builder;

@Builder
public record ModelInfo(String id, Integer quota) {

	public static ModelInfo of(String id) {
		return new ModelInfo(id, null);
	}

	public static ModelInfo of(String id, int quota) {
		return new ModelInfo(id, quota);
	}
}
