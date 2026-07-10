package dev.suprim.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 86400, cleanupCron = "0 0 */6 * * *")
class SessionConfig {
}
