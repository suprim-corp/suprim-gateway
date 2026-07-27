package dev.suprim.gateway.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Accumulates listing entries in insertion order, keeping the first entry seen
 * for an id. Providers are asked in priority order, so an earlier provider's
 * metadata for a shared model id wins.
 */
class ModelListing {

	private final List<ModelForListingApi> models = new ArrayList<>();
	private final LinkedHashSet<String> seen = new LinkedHashSet<>();
	private final long created;

	ModelListing(long created) {
		this.created = created;
	}

	void add(String id, String provider, String displayName) {
		if (id == null || !seen.add(id)) {
			return;
		}
		models.add(
				ModelForListingApi.builder()
				                  .id(id)
				                  .object("model")
				                  .ownedBy(provider)
				                  .created(created)
				                  .displayName(displayName)
				                  .build()
		);
	}

	List<ModelForListingApi> models() {
		return List.copyOf(models);
	}
}
