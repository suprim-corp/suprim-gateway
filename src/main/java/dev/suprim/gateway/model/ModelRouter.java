package dev.suprim.gateway.model;

import dev.suprim.gateway.auth.Provider;

public class ModelRouter {

	public static Provider resolveProvider(String model) {
		if (model != null && model.startsWith("gemini-")) {
			return Provider.ANTIGRAVITY;
		}
		return Provider.KIRO;
	}
}
