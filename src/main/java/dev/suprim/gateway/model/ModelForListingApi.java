package dev.suprim.gateway.model;

import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** One entry of the aggregated /v1/models listing. */
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelForListingApi(
		String id,
		String object,
		String ownedBy,
		long created,
		String displayName
) {}
