package dev.suprim.gateway.config;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final String TOP = "╔" + "═".repeat(WIDTH) + "╗";
    private static final String BOT = "╚" + "═".repeat(WIDTH) + "╝";
    private static final String SEP = "╠" + "─".repeat(WIDTH) + "╣";

    private final CredentialStore credentialStore;
    private final ProxyChain proxyChain;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void print() {
        String port = environment.getProperty("server.port", "8080");
        String version = environment.getProperty("info.app.version", "dev");

        log.info("");
        log.info("{}{}{}", CYAN, TOP, RESET);
        logRow(centerText("SUPRIM GATEWAY - v" + version), BOLD + CYAN);
        log.info("{}{}{}", CYAN, SEP, RESET);

        logProviders();

        log.info("{}{}{}", CYAN, SEP, RESET);
        logSystem(port);
        log.info("{}{}{}", CYAN, BOT, RESET);
        log.info("");
    }

    private void logProviders() {
        for (Provider provider : List.of(Provider.KIRO, Provider.ANTIGRAVITY, Provider.XAI, Provider.CODEX)) {
            long count = credentialStore.findAllByProvider(provider.name()).size();
            String label = String.format("%-14s", provider.name());
            String value = count > 0
                    ? GREEN + BOLD + count + " account" + (count > 1 ? "s" : "") + RESET
                    : DIM + "none" + RESET;
            logRow("  " + label + value, null);
        }
    }

    private void logSystem(String port) {
        String proxyStatus = proxyChain.hasProxies()
                ? GREEN + "active" + RESET
                : DIM + "direct" + RESET;

        logRow("  Port           " + YELLOW + port + RESET, null);
        logRow("  Proxy          " + proxyStatus, null);
    }

    private void logRow(String content, String color) {
        int visibleLen = content.replaceAll("\\[[;\\d]*m", "").length();
        int pad = Math.max(0, WIDTH - visibleLen);
        String padded = content + " ".repeat(pad);
        String prefix = color != null ? color : "";
        String suffix = color != null ? RESET : "";
        log.info("{}║{}{}{}{}{}║{}", CYAN, RESET, prefix, padded, suffix, CYAN, RESET);
    }

    private static String centerText(String text) {
        int totalPad = WIDTH - text.length();
        int left = totalPad / 2;
        int right = totalPad - left;
        return " ".repeat(Math.max(0, left)) + text + " ".repeat(Math.max(0, right));
    }
}
