package dev.suprim.gateway.provider.kiro.utils;

import dev.suprim.gateway.provider.kiro.model.KiroTool;
import dev.suprim.gateway.proxy.Tool;

import java.util.ArrayList;
import java.util.List;

public class ToolConverter {
	private ToolConverter() {}

	/**
	 * Upstream rejects a tool specification whose description grows past roughly ten
	 * thousand characters. Longer descriptions are moved to the system prompt instead of
	 * being truncated, so the model keeps the full documentation.
	 */
	static final int MAX_INLINE_DESCRIPTION_CHARS = 10_000;
	private static final String DOCUMENTATION_SECTION_PREFIX = "## Tool: ";

	/**
	 * @param tools         converted tool specifications, in input order
	 * @param documentation offloaded descriptions to append to the system prompt, empty
	 *                      when every description fits inline. Deterministic for a given
	 *                      tool list because session identity is keyed on the system prompt.
	 */
	public record ConversionResult(
			List<KiroTool> tools,
			String documentation
	) {}

	public static ConversionResult convert(List<Tool> tools) {
		if (tools == null || tools.isEmpty()) {
			return new ConversionResult(List.of(), "");
		}

		List<KiroTool> result = new ArrayList<>(tools.size());
		StringBuilder documentation = new StringBuilder();

		for (Tool tool : tools) {
			KiroTool kiroTool = convert(tool, documentation);
			if (kiroTool != null) {
				result.add(kiroTool);
			}
		}

		return new ConversionResult(
				List.copyOf(result),
				documentation.toString()
		);
	}

	private static KiroTool convert(Tool tool, StringBuilder documentation) {
		if (!"function".equals(tool.type())) {
			return null;
		}

		Tool.Function function = tool.function();
		if (function == null || function.name() == null ||
		    function.name().isBlank()
		) {
			return null;
		}

		String description = describe(function, documentation);

		KiroTool.InputSchema inputSchema =
				KiroTool.InputSchema.builder()
				                    .json(
						                    InputSchemaHandler.buildSchemaJson(
								                    function.parameters()
						                    )
				                    )
				                    .build();

		return KiroTool.builder()
		               .toolSpecification(
				               KiroTool.ToolSpecification.builder()
				                                         .name(function.name())
				                                         .description(
						                                         description
				                                         )
				                                         .inputSchema(
						                                         inputSchema
				                                         )
				                                         .build()
		               )
		               .build();
	}

	private static String describe(
			Tool.Function function,
			StringBuilder documentation
	) {
		String description = function.description();
		if (description == null || description.isBlank()) {
			return "Tool: " + function.name();
		}
		if (description.length() <= MAX_INLINE_DESCRIPTION_CHARS) {
			return description;
		}

		String section = DOCUMENTATION_SECTION_PREFIX + function.name();
		if (!documentation.isEmpty()) {
			documentation.append("\n\n");
		}
		documentation.append(section).append("\n\n").append(description);
		return "[Full documentation in system prompt under '" + section + "']";
	}
}
