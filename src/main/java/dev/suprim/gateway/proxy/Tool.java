package dev.suprim.gateway.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tool(
		String type,
		Function function
) {

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Function(
			String name,
			String description,
			JsonNode parameters,
			Boolean strict
	) {}
}
