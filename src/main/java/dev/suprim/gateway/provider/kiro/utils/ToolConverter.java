package dev.suprim.gateway.provider.kiro.utils;

import dev.suprim.gateway.provider.kiro.model.KiroTool;
import dev.suprim.gateway.proxy.Tool;

import java.util.ArrayList;
import java.util.List;

public class ToolConverter {
	private ToolConverter() {}

	private static final int MAX_DESCRIPTION_LEN = 10237;

	public static List<KiroTool> convert(List<Tool> tools) {
		if (tools == null || tools.isEmpty()) {
			return List.of();
		}

		List<KiroTool> result = new ArrayList<>(tools.size());

		for (Tool tool : tools) {
			KiroTool kiroTool = convert(tool);
			if (kiroTool != null) {
				result.add(kiroTool);
			}
		}

		return result;
	}

	public static KiroTool convert(Tool tool) {
		if (!"function".equals(tool.type())) {
			return null;
		}

		Tool.Function function = tool.function();
		if (function == null || function.name() == null ||
		    function.name().isBlank()
		) {
			return null;
		}

		String description = function.description();
		if (description == null || description.isBlank()) {
			description = "Tool: " + function.name();
		} else if (description.length() > MAX_DESCRIPTION_LEN) {
			description = description.substring(0, MAX_DESCRIPTION_LEN) + "...";
		}

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
}
