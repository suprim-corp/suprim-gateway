package dev.suprim.gateway.api;

import dev.suprim.gateway.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class ModelsController {

	private final ModelRegistry modelRegistry;

	ModelsController(ModelRegistry modelRegistry) {
		this.modelRegistry = modelRegistry;
	}

	@GetMapping("/v1/models")
	Map<String, Object> models() {
		List<Map<String, Object>> data =
				modelRegistry.getAvailableModels()
				             .stream()
				             .map(id -> Map.<String, Object>of(
						             "id",
						             id,
						             "object",
						             "model",
						             "created",
						             1700000000,
						             "owned_by",
						             "kiro"
				             ))
				             .toList();
		return Map.of("object", "list", "data", data);
	}
}
