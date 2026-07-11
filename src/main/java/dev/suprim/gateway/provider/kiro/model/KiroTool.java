package dev.suprim.gateway.provider.kiro.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KiroTool(
		ToolSpecification toolSpecification
) {

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolSpecification(
			String name,
			String description,
			InputSchema inputSchema
	) {}

	/**
	 * Union type with a single "json" member holding an arbitrary JSON Schema object.
	 * JsonNode is used because JSON Schema is recursive/dynamic with no fixed shape to model as a record.
	 *
	 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ToolInputSchema.html">ToolInputSchema</a>
	 */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InputSchema(
			JsonNode json
	) {}
}
