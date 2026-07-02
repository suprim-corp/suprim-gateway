package dev.suprim.gateway.admin;

import dev.suprim.gateway.logging.RequestLogService;
import dev.suprim.gateway.logging.RequestLog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
class LogsController {

    private static final int PAGE_SIZE = 50;
    private final RequestLogService logService;

    LogsController(RequestLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/logs")
    String logs(@RequestParam(defaultValue = "1") int page, Model model) {
        int offset = (page - 1) * PAGE_SIZE;
        List<RequestLog> logs = logService.getLogs(PAGE_SIZE, offset);
        int total = logService.getTotal();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);

        model.addAttribute("logs", logs);
        model.addAttribute("total", total);
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("view", "logs");
        model.addAttribute("currentPage", "logs");
        model.addAttribute("pageTitle", "Logs");
        return "layout";
    }
}
