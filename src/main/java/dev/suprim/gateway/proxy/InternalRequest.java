package dev.suprim.gateway.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternalRequest(
		String model,
		List<Message> messages,
		boolean stream,
		List<?> tools,
		Double temperature,
		@JsonProperty("max_tokens") Integer maxTokens
) {}
