package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.kiro.ImportRequest;
import dev.suprim.gateway.provider.kiro.ImportResult;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final KiroAuthManager authManager;
	private final CredentialStore credentialStore;

	@PostMapping("/auth/import")
	String importCredentials(
			@RequestParam("files") List<MultipartFile> files,
			@RequestParam(required = false) String name,
			RedirectAttributes redirectAttributes
	) {
		if (files.isEmpty() || files.size() > 2) {
			redirectAttributes.addFlashAttribute(
					"error",
					"Expected 1 or 2 files"
			);
			return "redirect:/providers";
		}

		try {
			String refreshToken = null;
			String region = null;
			String clientId = null;
			String clientSecret = null;
			String profileArn = null;

			for (MultipartFile file : files) {
				JsonNode json = mapper.readTree(file.getBytes());
				if (json.has("refreshToken")) {
					refreshToken = json.get("refreshToken").asString();
					region = json.has("region") ? json.get("region")
					                                  .asString() : null;
				} else if (json.has("clientId")) {
					clientId = json.get("clientId").asString();
					clientSecret = json.has("clientSecret") ? json.get(
							"clientSecret").asString() : null;
				}
			}

			if (refreshToken == null) {
				redirectAttributes.addFlashAttribute(
						"error",
						"No file with refreshToken found"
				);
				return "redirect:/providers";
			}

			ImportRequest req = ImportRequest.builder()
			                                 .refreshToken(refreshToken)
			                                 .clientId(clientId)
			                                 .clientSecret(clientSecret)
			                                 .region(region)
			                                 .profileArn(profileArn)
			                                 .build();
			ImportResult result = authManager.importAccount(req);

			StoredAccount imported = result.account();
			StoredAccount withMeta =
					StoredAccount.builder()
					             .name(name != null &&
					                   !name.isBlank() ? name.trim() : null
					             )
					             .profileArn(imported.profileArn())
					             .authType(imported.authType())
					             .clientId(imported.clientId())
					             .clientSecret(imported.clientSecret())
					             .accessToken(imported.accessToken())
					             .refreshToken(imported.refreshToken())
					             .expiresAt(imported.expiresAt())
					             .scopes(imported.scopes())
					             .region(imported.region())
					             .apiRegion(imported.apiRegion())
					             .provider(Provider.KIRO.name())
					             .projectId(imported.projectId())
					             .build();
			credentialStore.upsert(withMeta);
			return "redirect:/providers";
		} catch (Exception e) {
			log.warn("[Auth] Import failed: {}", e.getMessage());
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return "redirect:/providers";
		}
	}
}
