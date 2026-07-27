package dev.suprim.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One entry of the aggregated /v1/models listing. The core fields are the
 * OpenAI listing shape; {@code capabilities} and the token limits are an
 * extension carrying whatever the upstream provider reported, omitted entirely
 * for providers that report nothing.
 */
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelForListingApi(
		String id,
		String object,
		String ownedBy,
		long created,
		String displayName,
		Integer maxInputTokens,
		Integer maxOutputTokens,
		ModelCapabilities capabilities
) {}
