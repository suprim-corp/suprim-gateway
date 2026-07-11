package dev.suprim.gateway.api;

import dev.suprim.gateway.model.ModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RequiredArgsConstructor
@RestController
class ModelsController {

	private final ModelRegistry modelRegistry;

	@GetMapping("/v1/models")
	Map<String, Object> models() {
		return Map.of(
				"object",
				"list",
				"data",
				modelRegistry.getAllModelsForApi()
		);
	}
}
