package dev.suprim.gateway.admin;

import dev.suprim.gateway.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.auth.KiroCredentialStore;
import dev.suprim.gateway.auth.StoredAccount;
import dev.suprim.gateway.model.ModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Controller
class ProvidersController {

	private final KiroCredentialStore credentialStore;
	private final ModelRegistry modelRegistry;
	private final AntigravityAuthManager antigravityAuthManager;

	@GetMapping("/providers")
	String providers(Model model) {
		List<StoredAccount> accounts = credentialStore.load();
		model.addAttribute("accounts", accounts);
		model.addAttribute("view", "providers");
		model.addAttribute("currentPage", "providers");
		model.addAttribute("pageTitle", "Providers");
		return "layout";
	}

	@PostMapping("/providers/{index}/rename")
	String rename(@PathVariable int index, @RequestParam String name) {
		List<StoredAccount> accounts = credentialStore.load();
		if (index >= 0 && index < accounts.size()) {
			List<StoredAccount> updated = new ArrayList<>(accounts);
			updated.set(index, accounts.get(index).withName(name.trim()));
			credentialStore.save(updated);
		}
		return "redirect:/providers";
	}

	@PostMapping("/providers/{index}/delete")
	String delete(@PathVariable int index) {
		List<StoredAccount> accounts = credentialStore.load();
		if (index >= 0 && index < accounts.size()) {
			List<StoredAccount> updated = new ArrayList<>(accounts);
			updated.remove(index);
			credentialStore.save(updated);
		}
		return "redirect:/providers";
	}

	@PostMapping("/providers/add-key")
	String addKey(@RequestParam String provider, @RequestParam String apiKey) {
		StoredAccount account = StoredAccount.builder()
		                                     .provider(provider)
		                                     .accessToken(apiKey.trim())
		                                     .authType("api_key")
		                                     .build();
		credentialStore.upsert(account);
		return "redirect:/providers";
	}

	@GetMapping("/providers/{index}/models")
	@ResponseBody
	List<Map<String, Object>> models(@PathVariable int index) throws Exception {
		List<StoredAccount> accounts = credentialStore.load();
		if (index < 0 || index >= accounts.size()) {
			return List.of();
		}
		StoredAccount account = accounts.get(index);
		String provider = account.provider();
		if ("KIRO".equals(provider)) {
			return modelRegistry.getAvailableModels().stream()
			                    .map(id -> Map.<String, Object>of("id", id))
			                    .toList();
		}
		if ("ANTIGRAVITY".equals(provider)) {
			return antigravityAuthManager.listModels();
		}
		return List.of();
	}
}
