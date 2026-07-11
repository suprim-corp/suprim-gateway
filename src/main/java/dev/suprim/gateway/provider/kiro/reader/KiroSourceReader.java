package dev.suprim.gateway.provider.kiro.reader;

import dev.suprim.gateway.provider.kiro.KiroCredentials;
import dev.suprim.gateway.config.AppConfig;

import java.nio.file.Path;
import java.util.Optional;

public final class KiroSourceReader {

	private KiroSourceReader() {}

	public static Optional<KiroCredentials> read(AppConfig config) {
		if (config.cliDbFile() != null && !config.cliDbFile().isBlank()) {
			return SqliteCredentialReader.read(resolvePath(config.cliDbFile()));
		} else if (config.credsFile() != null &&
		           !config.credsFile().isBlank()) {
			return JsonCredentialReader.read(resolvePath(config.credsFile()));
		} else if (config.refreshToken() != null &&
		           !config.refreshToken().isBlank()) {
			return Optional.of(
					KiroCredentials.builder()
					               .refreshToken(config.refreshToken())
					               .authType(KiroCredentials.AuthType.KIRO_DESKTOP)
					               .build()
			);
		}
		return Optional.empty();
	}

	private static Path resolvePath(String path) {
		if (path.startsWith("~")) {
			return Path.of(System.getProperty("user.home"))
			           .resolve(path.substring(2));
		}
		return Path.of(path);
	}
}
