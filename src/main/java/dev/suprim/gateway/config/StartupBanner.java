package dev.suprim.gateway.config;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBanner {

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String DIM = "[2m";
    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";

    private static final int WIDTH = 52;
    private static final String BORDER = "═".repeat(WIDTH);
    private static final String THIN = "─".repeat(WIDTH);

    private final CredentialStore credentialStore;
    private final ProxyChain proxyChain;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void print() {
        String port = environment.getProperty("server.port", "8080");

        log.info("");
        log.info("{}{}{}", CYAN, BORDER, RESET);
        logCentered("KIRO GATEWAY  v0.2.0");
        log.info("{}{}{}", CYAN, THIN, RESET);

        logProviders();

        log.info("{}{}{}", CYAN, THIN, RESET);
        logSystem(port);
        log.info("{}{}{}", CYAN, BORDER, RESET);
        log.info("");
    }

    private void logProviders() {
        for (Provider provider : List.of(Provider.KIRO, Provider.ANTIGRAVITY, Provider.XAI, Provider.CODEX)) {
            long count = credentialStore.findAllByProvider(provider.name()).size();
            String status = count > 0
                    ? GREEN + BOLD + count + " account" + (count > 1 ? "s" : "") + RESET
                    : DIM + "none" + RESET;
            log.info("  {}{}{} {}", BOLD, String.format("%-14s", provider.name()), RESET, status);
        }
    }

    private void logSystem(String port) {
        String proxyStatus = proxyChain.hasProxies()
                ? GREEN + "active" + RESET
                : DIM + "direct" + RESET;

        log.info("  {}Port{}           {}{}{}", DIM, RESET, YELLOW, port, RESET);
        log.info("  {}Proxy{}          {}", DIM, RESET, proxyStatus);
    }

    private void logCentered(String text) {
        int padding = (WIDTH - text.length()) / 2;
        String centered = " ".repeat(Math.max(0, padding)) + text;
        log.info("{}{}{}{}", CYAN, BOLD, centered, RESET);
    }
}
