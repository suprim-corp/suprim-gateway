package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.model.ModelInfo;
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

	private final CredentialStore credentialStore;
	private final ModelRegistry modelRegistry;
	private final KiroAuthManager kiroAuthManager;
	private final CodexAuthManager codexAuthManager;

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
	@ResponseBody
	Map<String, String> rename(@PathVariable int index, @RequestParam String name) {
		List<StoredAccount> accounts = credentialStore.load();
		if (index < 0 || index >= accounts.size()) {
			return Map.of("error", "Invalid index");
		}
		String trimmed = name.trim();
		String provider = accounts.get(index).provider();
		boolean duplicate = accounts.stream()
				.anyMatch(acc -> trimmed.equals(acc.name()) && provider.equals(acc.provider()));
		if (duplicate) {
			return Map.of("error", "Name already exists for this provider");
		}
		List<StoredAccount> updated = new ArrayList<>(accounts);
		updated.set(index, accounts.get(index).withName(trimmed));
		credentialStore.save(updated);
		return Map.of("ok", "true");
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
	String addKey(
			@RequestParam String provider,
			@RequestParam String apiKey
	) {
		String trimmedKey = apiKey.trim();
		String name = null;
		String profileArn = null;
		if ("KIRO".equals(provider)) {
			name = kiroAuthManager.fetchEmailForApiKey(trimmedKey);
			profileArn = kiroAuthManager.resolveProfileArnForApiKey(trimmedKey);
		}
		StoredAccount account = StoredAccount.builder()
		                                     .provider(provider)
		                                     .accessToken(trimmedKey)
		                                     .authType("api_key")
		                                     .name(name)
		                                     .profileArn(profileArn)
		                                     .build();
		credentialStore.upsert(account);
		return "redirect:/providers";
	}

	@GetMapping("/providers/{index}/models")
	@ResponseBody
	List<ModelInfo> models(@PathVariable int index) throws Exception {
		List<StoredAccount> accounts = credentialStore.load();
		if (index < 0 || index >= accounts.size()) {
			return List.of();
		}
		StoredAccount account = accounts.get(index);
		return modelRegistry.getModelsForProvider(account);
	}

	@GetMapping("/providers/{index}/usage")
	@ResponseBody
	Map<String, Object> usage(@PathVariable int index) {
		List<StoredAccount> accounts = credentialStore.load();
		if (index < 0 || index >= accounts.size()) {
			return Map.of();
		}
		StoredAccount account = accounts.get(index);
		return switch (Provider.valueOf(account.provider())) {
			case KIRO -> kiroAuthManager.getUsageLimits(account);
			case CODEX -> codexAuthManager.getUsageLimits(account);
			default -> Map.of();
		};
	}
}
