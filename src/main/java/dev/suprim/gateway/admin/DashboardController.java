package dev.suprim.gateway.admin;

import dev.suprim.gateway.logging.RequestLogService;
import dev.suprim.gateway.virtualkey.VirtualKey;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Controller
class DashboardController {

    private final RequestLogService logService;
    private final VirtualKeyService keyService;

    DashboardController(RequestLogService logService, VirtualKeyService keyService) {
        this.logService = logService;
        this.keyService = keyService;
    }

    @GetMapping("/")
    String dashboard(Model model) {
        Map<String, Object> stats = logService.getStats();
        List<Map<String, Object>> timeSeries = formatTimeSeries(logService.getTimeSeries(24));
        List<Map<String, Object>> models = logService.getModelUsage();
        List<Map<String, Object>> topKeys = logService.getTopKeys();
        long activeKeys = keyService.list(1000, 0).stream().filter(k -> k.enabled() && k.revokedAt() == null).count();

        long uptimeSeconds = ((Number) stats.get("uptimeSeconds")).longValue();
        String uptime = uptimeSeconds >= 3600
                ? (uptimeSeconds / 3600) + "h " + ((uptimeSeconds % 3600) / 60) + "m"
                : (uptimeSeconds / 60) + "m";

        model.addAttribute("stats", stats);
        model.addAttribute("timeSeries", timeSeries);
        model.addAttribute("models", models);
        model.addAttribute("topKeys", topKeys);
        model.addAttribute("activeKeys", activeKeys);
        model.addAttribute("uptime", uptime);
        model.addAttribute("view", "dashboard");
        model.addAttribute("currentPage", "overview");
        model.addAttribute("pageTitle", "Overview");
        return "layout";
    }

    private List<Map<String, Object>> formatTimeSeries(List<Map<String, Object>> raw) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
        return raw.stream().map(row -> {
            long bucket = ((Number) row.get("bucket")).longValue();
            String time = formatter.format(Instant.ofEpochMilli(bucket));
            return Map.<String, Object>of(
                    "time", time,
                    "requests", row.get("requests"),
                    "errors", row.get("errors"),
                    "tokens", row.get("tokens"),
                    "cost", row.get("cost")
            );
        }).toList();
    }
}
