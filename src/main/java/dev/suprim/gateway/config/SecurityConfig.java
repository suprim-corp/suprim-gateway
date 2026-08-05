package dev.suprim.gateway.config;

import dev.suprim.gateway.virtualkey.VirtualKeyAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private final AppConfig appConfig;
    private final VirtualKeyAuthFilter virtualKeyAuthFilter;

    SecurityConfig(AppConfig appConfig, VirtualKeyAuthFilter virtualKeyAuthFilter) {
        this.appConfig = appConfig;
        this.virtualKeyAuthFilter = virtualKeyAuthFilter;
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/v1/**", "/health")
                .cors(cors -> cors.configurationSource(apiCorsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(virtualKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                );
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/error", "/*.html", "/favicon.ico").permitAll()
                        .requestMatchers("/auth/xai/agent", "/auth/xai/exchange", "/auth/xai/device-exchange").permitAll()
                        .requestMatchers("/auth/antigravity/agent", "/auth/antigravity/token-exchange").permitAll()
                        .requestMatchers("/auth/codex/agent", "/auth/codex/exchange", "/auth/codex/device-exchange", "/auth/codex/state").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login")
                        .permitAll()
                );
        return http.build();
    }

    /**
     * Browser-hosted clients (Claude for Office, web playgrounds) send a preflight OPTIONS
     * before every /v1 call. Credentials travel in headers rather than cookies, so any origin
     * is acceptable: a request still needs a valid virtual key to get past the auth filter.
     */
    CorsConfigurationSource apiCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", config);
        source.registerCorsConfiguration("/health", config);
        return source;
    }

    @Bean
    UserDetailsService userDetailsService() {
        org.springframework.security.core.userdetails.UserDetails user = User.builder()
                .username("admin")
                .password("{noop}" + appConfig.adminApiKey())
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
