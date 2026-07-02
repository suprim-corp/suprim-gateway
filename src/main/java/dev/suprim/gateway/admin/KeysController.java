package dev.suprim.gateway.admin;

import dev.suprim.gateway.virtualkey.VirtualKey;
import dev.suprim.gateway.virtualkey.VirtualKeyService;
import dev.suprim.gateway.virtualkey.VirtualKeyService.CreateKeyResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
class KeysController {

	private static final int PAGE_SIZE = 20;
	private final VirtualKeyService keyService;

	KeysController(VirtualKeyService keyService) {
		this.keyService = keyService;
	}

	@GetMapping("/keys")
	String keys(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(required = false) String createdKey,
			Model model
	) {
		int offset = (page - 1) * PAGE_SIZE;
		List<VirtualKey> keys = keyService.list(PAGE_SIZE, offset);
		int total = keyService.count();
		int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);

		model.addAttribute("keys", keys);
		model.addAttribute("total", total);
		model.addAttribute("page", page);
		model.addAttribute("totalPages", totalPages);
		model.addAttribute("createdKey", createdKey);
		model.addAttribute("view", "keys");
		model.addAttribute("currentPage", "keys");
		model.addAttribute("pageTitle", "API Keys");
		return "layout";
	}

	@PostMapping("/keys/create")
	String create(
			@RequestParam String name,
			@RequestParam(defaultValue = "60") int rateLimitPerMin,
			RedirectAttributes redirectAttributes
	) {
		CreateKeyResult result = keyService.create(name, rateLimitPerMin);
		redirectAttributes.addAttribute("createdKey", result.rawKey());
		return "redirect:/keys";
	}

	@PostMapping("/keys/{id}/toggle")
	String toggle(@PathVariable String id) {
		keyService.toggle(id);
		return "redirect:/keys";
	}

	@PostMapping("/keys/{id}/revoke")
	String revoke(@PathVariable String id) {
		keyService.revoke(id);
		return "redirect:/keys";
	}

	@PostMapping("/keys/{id}/limits")
	String updateLimits(
			@PathVariable String id,
			@RequestParam int rateLimitPerMin,
			@RequestParam(required = false) String budgetPeriod,
			@RequestParam(required = false) Integer budgetTokens,
			@RequestParam(required = false) Integer budgetRequests,
			@RequestParam(required = false) Double budgetCost
	) {
		keyService.updateLimits(
				id, rateLimitPerMin,
				budgetPeriod != null &&
				budgetPeriod.isBlank() ? null : budgetPeriod,
				budgetTokens, budgetRequests,
				budgetCost != null ? (int) Math.round(budgetCost * 100) : null
		);
		return "redirect:/keys";
	}
}
