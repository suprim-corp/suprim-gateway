package dev.suprim.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "kiro")
public record AppConfig(
		String adminApiKey,
		String credsFile,
		String refreshToken,
		String cliDbFile,
		String profileArn,
		String region,
		String apiRegion,
		String proxyFile,
		int firstTokenTimeout,
		int streamingReadTimeout,
		int firstTokenMaxRetries,
		String disabledModels
) {
	public AppConfig {
		if (region == null || region.isBlank()) region = "us-east-1";
		if (apiRegion == null || apiRegion.isBlank()) apiRegion = region;
		if (firstTokenTimeout <= 0) firstTokenTimeout = 15;
		if (streamingReadTimeout <= 0) streamingReadTimeout = 300;
		if (firstTokenMaxRetries <= 0) firstTokenMaxRetries = 3;
		if (disabledModels == null) disabledModels = "";
	}

	public Set<String> disabledModelsSet() {
		return Arrays.stream(disabledModels.split(","))
		             .map(String::trim)
		             .filter(s -> !s.isEmpty())
		             .collect(Collectors.toSet());
	}
}
