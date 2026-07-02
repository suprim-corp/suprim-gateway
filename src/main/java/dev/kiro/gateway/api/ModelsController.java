package dev.kiro.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class ModelsController {

    private static final List<String> MODELS = List.of(
            "claude-sonnet-4-5", "claude-haiku-4-5", "claude-sonnet-4",
            "deepseek-v3-2", "glm-5", "qwen3-coder-next"
    );

    @GetMapping("/v1/models")
    Map<String, Object> models() {
        List<Map<String, Object>> data = MODELS.stream().map(id -> Map.<String, Object>of(
                "id", id,
                "object", "model",
                "created", System.currentTimeMillis() / 1000,
                "owned_by", "kiro"
        )).toList();
        return Map.of("object", "list", "data", data);
    }
}
