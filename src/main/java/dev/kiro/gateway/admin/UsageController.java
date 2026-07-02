package dev.kiro.gateway.admin;

import dev.kiro.gateway.logging.RequestLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Controller
class UsageController {

    private final RequestLogService logService;

    UsageController(RequestLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/usage")
    String usage(@RequestParam(defaultValue = "24") int hours, Model model) {
        List<Map<String, Object>> timeSeries = formatTimeSeries(logService.getTimeSeries(hours));
        List<Map<String, Object>> models = logService.getModelUsage();
        List<Map<String, Object>> topKeys = logService.getTopKeys();

        double totalCost = timeSeries.stream().mapToDouble(m -> ((Number) m.get("cost")).doubleValue()).sum();
        long totalTokens = timeSeries.stream().mapToLong(m -> ((Number) m.get("tokens")).longValue()).sum();
        long totalRequests = timeSeries.stream().mapToLong(m -> ((Number) m.get("requests")).longValue()).sum();

        model.addAttribute("hours", hours);
        model.addAttribute("timeSeries", timeSeries);
        model.addAttribute("models", models);
        model.addAttribute("topKeys", topKeys);
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("totalTokens", totalTokens);
        model.addAttribute("totalRequests", totalRequests);
        model.addAttribute("view", "usage");
        model.addAttribute("currentPage", "usage");
        model.addAttribute("pageTitle", "Usage");
        return "layout";
    }

    private List<Map<String, Object>> formatTimeSeries(List<Map<String, Object>> raw) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());
        return raw.stream().map(row -> {
            long bucket = ((Number) row.get("bucket")).longValue();
            String time = formatter.format(Instant.ofEpochMilli(bucket));
            return Map.<String, Object>of(
                    "time", time,
                    "requests", row.get("requests"),
                    "tokens", row.get("tokens"),
                    "cost", row.get("cost")
            );
        }).toList();
    }
}
