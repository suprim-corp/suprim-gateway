package dev.suprim.gateway.config;

import dev.suprim.gateway.proxy.ProxyChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
class ProxyConfig {

	@Bean
	ProxyChain proxyChain(
			AppConfig config,
			@Value("${spring.datasource.url}") String datasourceUrl
	) {
		String proxyFile = config.proxyFile();
		if (proxyFile != null && !proxyFile.isBlank()) {
			return ProxyChain.of(Path.of(proxyFile));
		}
		String dbPath = datasourceUrl.replace("jdbc:sqlite:", "");
		Path dataDir = Path.of(dbPath).getParent();
		return ProxyChain.of(dataDir.resolve("proxies.json"));
	}
}
