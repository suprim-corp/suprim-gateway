package dev.suprim.gateway.provider;

import lombok.Getter;

@Getter
public enum Provider {

	KIRO(null),
	ANTIGRAVITY("ag/"),
	GROK("grok/"),
	XAI("grok/"),
	CODEX("codex/");

	private final String prefix;

	Provider(String prefix) {
		this.prefix = prefix;
	}

	public static Provider fromModel(String model) {
		if (model == null || model.isEmpty()) {
			return KIRO;
		}
		for (Provider provider : values()) {
			if (provider.prefix != null && model.startsWith(provider.prefix)) {
				return provider;
			}
		}
		return KIRO;
	}

	public static String stripPrefix(String model) {
		if (model == null) {
			return null;
		}
		for (Provider provider : values()) {
			if (provider.prefix != null && model.startsWith(provider.prefix)) {
				return model.substring(provider.prefix.length());
			}
		}
		return model;
	}
}
