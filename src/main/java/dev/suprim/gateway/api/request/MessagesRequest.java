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
public record MessagesRequest(
		@NotBlank String model,
		@JsonProperty("max_tokens") @NotNull Integer maxTokens,
		@NotNull @Valid List<Message> messages,
		JsonNode system,
		Boolean stream,
		Double temperature,
		@JsonProperty("top_p") Double topP,
		@JsonProperty("top_k") Integer topK,
		@JsonProperty("stop_sequences") List<String> stopSequences,
		@Valid List<Tool> tools,
		@JsonProperty("tool_choice") JsonNode toolChoice,
		JsonNode metadata,
		@JsonAnySetter @JsonAnyGetter Map<String, Object> additionalProperties
) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Message(
			@NotBlank String role,
			JsonNode content
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Tool(
			@NotBlank String name,
			String description,
			@JsonProperty("input_schema") JsonNode inputSchema
	) {}
}
