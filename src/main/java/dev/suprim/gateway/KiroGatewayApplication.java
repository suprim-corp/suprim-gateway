package dev.suprim.gateway;

import dev.suprim.gateway.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppConfig.class)
public class KiroGatewayApplication {

	static void main(String[] args) {
		SpringApplication.run(KiroGatewayApplication.class, args);
	}
}
