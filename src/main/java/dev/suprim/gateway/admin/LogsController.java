package dev.suprim.gateway.admin;

import dev.suprim.gateway.logging.RequestLog;
import dev.suprim.gateway.logging.RequestLogService;
import dev.suprim.gateway.utils.PricingService;
import dev.suprim.gateway.virtualkey.VirtualKey;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Controller
class LogsController {

	private static final int PAGE_SIZE = 50;
	private final RequestLogService logService;
	private final PricingService pricingService;
	private final VirtualKeyService virtualKeyService;

	@Builder
	public record LogRow(
			String id, String virtualKeyId, String keyName, String accountId,
			String model, String requestedModel, int status, Integer latencyMs,
			Integer firstTokenMs, Boolean streaming, String clientIp,
			String errorMessage, Double credits, long createdAt, Usage usage
	) {}

	@Builder
	public record Usage(
			Integer promptTokens, Integer completionTokens,
			Integer totalTokens, Double cost
	) {}

	@GetMapping("/logs")
	String logs(@RequestParam(defaultValue = "1") int page, Model model) {
		int offset = (page - 1) * PAGE_SIZE;
		List<RequestLog> logs = logService.getLogs(PAGE_SIZE, offset);
		int total = logService.getTotal();
		int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);

		Map<String, String> keyNames = virtualKeyService.list(1000, 0).stream()
				.collect(Collectors.toMap(VirtualKey::id, VirtualKey::name, (a, b) -> a));

		List<LogRow> rows = logs.stream()
		                        .map(l -> LogRow.builder()
				                        .id(l.id())
				                        .virtualKeyId(l.virtualKeyId())
				                        .keyName(l.virtualKeyId() != null ? keyNames.getOrDefault(l.virtualKeyId(), l.virtualKeyId()) : null)
				                        .accountId(l.accountId())
				                        .model(l.model())
				                        .requestedModel(l.requestedModel())
				                        .status(l.status())
				                        .latencyMs(l.latencyMs())
				                        .firstTokenMs(l.firstTokenMs())
				                        .streaming(l.streaming())
				                        .clientIp(l.clientIp())
				                        .errorMessage(l.errorMessage())
				                        .credits(l.credits())
				                        .createdAt(l.createdAt())
				                        .usage(Usage.builder()
						                        .promptTokens(l.promptTokens())
						                        .completionTokens(l.completionTokens())
						                        .totalTokens(l.totalTokens())
						                        .cost(l.promptTokens() != null && l.completionTokens() != null
								                        ? pricingService.calculateCost(l.model(), l.promptTokens(), l.completionTokens())
								                        : null)
						                        .build())
				                        .build())
		                        .toList();

		model.addAttribute("logs", rows);
		model.addAttribute("total", total);
		model.addAttribute("page", page);
		model.addAttribute("totalPages", totalPages);
		model.addAttribute("view", "logs");
		model.addAttribute("currentPage", "logs");
		model.addAttribute("pageTitle", "Logs");
		return "layout";
	}
}
