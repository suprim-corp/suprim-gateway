package dev.suprim.gateway.config;

import dev.suprim.gateway.proxy.ProxyChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
class ProxyConfig {

	@Bean
	ProxyChain proxyChain(AppConfig config) {
		String proxyFile = config.proxyFile();
		Path path = (proxyFile == null || proxyFile.isBlank())
				? Path.of("data/proxies.json")
				: Path.of(proxyFile);
		return ProxyChain.of(path);
	}
}
