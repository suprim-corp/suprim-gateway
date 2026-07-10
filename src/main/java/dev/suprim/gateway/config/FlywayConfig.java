package dev.suprim.gateway.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FlywayConfig {

	@Bean
	FlywayMigrationStrategy flywayMigrationStrategy() {
		return flyway -> {
			flyway.repair();
			flyway.migrate();
		};
	}
}
