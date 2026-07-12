package dev.suprim.gateway.api.request;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponsesRequest(
		@NotBlank String model,
		@NotNull JsonNode input,
		Boolean stream,
		String instructions,
		Double temperature,
		@JsonProperty("top_p") Double topP,
		@JsonProperty("max_output_tokens") Integer maxOutputTokens,
		List<@Valid Tool> tools,
		@JsonProperty("tool_choice") JsonNode toolChoice,
		@JsonProperty("previous_response_id") String previousResponseId,
		Reasoning reasoning,
		Map<String, String> metadata,
		@JsonAnySetter @JsonAnyGetter Map<String, Object> additionalProperties
) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Tool(
			@NotBlank String type,
			String name,
			String description,
			JsonNode parameters,
			Boolean strict,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> additionalProperties
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Reasoning(
			String effort,
			String summary
	) {}
}
