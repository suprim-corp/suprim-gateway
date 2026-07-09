package dev.suprim.gateway.admin;

import dev.suprim.gateway.auth.ImportRequest;
import dev.suprim.gateway.auth.ImportResult;
import dev.suprim.gateway.auth.KiroAuthManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final KiroAuthManager authManager;

	@GetMapping("/auth/import")
	String importPage(Model model) {
		model.addAttribute("view", "import");
		model.addAttribute("currentPage", "import");
		model.addAttribute("pageTitle", "Import Credentials");
		return "layout";
	}

	@PostMapping("/auth/import")
	String importCredentials(@RequestParam("files") List<MultipartFile> files, Model model) {
		model.addAttribute("view", "import");
		model.addAttribute("currentPage", "import");
		model.addAttribute("pageTitle", "Import Credentials");

		if (files.isEmpty() || files.size() > 2) {
			model.addAttribute("error", "Expected 1 or 2 files");
			return "layout";
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
					region = json.has("region") ? json.get("region").asString() : null;
				} else if (json.has("clientId")) {
					clientId = json.get("clientId").asString();
					clientSecret = json.has("clientSecret") ? json.get("clientSecret").asString() : null;
				}
			}

			if (refreshToken == null) {
				model.addAttribute("error", "No file with refreshToken found");
				return "layout";
			}

			ImportRequest req = ImportRequest.builder()
			                                 .refreshToken(refreshToken)
			                                 .clientId(clientId)
			                                 .clientSecret(clientSecret)
			                                 .region(region)
			                                 .profileArn(profileArn)
			                                 .build();
			ImportResult result = authManager.importAccount(req);

			model.addAttribute("success", true);
			model.addAttribute("profileArn", result.profileArn() != null ? result.profileArn() : "");
			model.addAttribute("authType", result.authType());
			model.addAttribute("isNew", result.isNew());
			return "layout";
		} catch (Exception e) {
			log.warn("[Auth] Import failed: {}", e.getMessage());
			model.addAttribute("error", e.getMessage());
			return "layout";
		}
	}
}
