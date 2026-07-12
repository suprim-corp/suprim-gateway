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
public record CompletionsRequest(
		@NotBlank String model,
		@NotNull List<@Valid Message> messages,
		Boolean stream,
		Double temperature,
		@JsonProperty("top_p") Double topP,
		Integer n,
		@JsonProperty("max_tokens") Integer maxTokens,
		JsonNode stop,
		@JsonProperty("frequency_penalty") Double frequencyPenalty,
		@JsonProperty("presence_penalty") Double presencePenalty,
		@JsonProperty("logit_bias") Map<String, Integer> logitBias,
		List<@Valid Tool> tools,
		@JsonProperty("tool_choice") JsonNode toolChoice,
		@JsonProperty("response_format") JsonNode responseFormat,
		String user,
		Integer seed,
		@JsonAnySetter @JsonAnyGetter Map<String, Object> additionalProperties
) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Message(
			@NotBlank String role,
			JsonNode content,
			String name,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls,
			@JsonProperty("tool_call_id") String toolCallId
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCall(
			String id,
			String type,
			FunctionCall function
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionCall(
			String name,
			String arguments
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Tool(
			String type,
			FunctionDef function
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionDef(
			@NotBlank String name,
			String description,
			JsonNode parameters,
			Boolean strict
	) {}
}
