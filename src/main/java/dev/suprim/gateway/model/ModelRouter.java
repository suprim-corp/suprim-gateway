package dev.suprim.gateway.model;

import dev.suprim.gateway.auth.Provider;

public class ModelRouter {

	public static Provider resolveProvider(String model) {
		return Provider.fromModel(model);
	}

	public static String stripPrefix(String model) {
		return Provider.stripPrefix(model);
	}
}
