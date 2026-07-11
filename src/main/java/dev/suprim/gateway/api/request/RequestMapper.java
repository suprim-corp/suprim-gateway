package dev.suprim.gateway.api.request;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class RequestMapper {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

	private RequestMapper() {}

	public static List<Map<String, Object>> toolsToList(List<?> tools) {
		if (tools == null) return null;
		return MAPPER.convertValue(tools, LIST_MAP_TYPE);
	}

	public static List<Map<String, Object>> messagesToList(List<?> messages) {
		return toolsToList(messages);
	}

	public static Map<String, Object> toMap(Object request) {
		return MAPPER.convertValue(request, MAP_TYPE);
	}

	public static List<Object> toList(Object value) {
		if (value == null) return null;
		return MAPPER.convertValue(value, new TypeReference<>() {});
	}
}
