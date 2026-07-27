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
		add(ModelForListingApi.builder()
		                      .id(id)
		                      .ownedBy(provider)
		                      .displayName(displayName));
	}

	/**
	 * Adds an entry from a partially-built model, filling in the fields every
	 * entry shares. Callers that have capability or token-limit data set it on
	 * the builder first.
	 */
	void add(ModelForListingApi.ModelForListingApiBuilder builder) {
		ModelForListingApi model = builder.object("model")
		                                  .created(created)
		                                  .build();
		if (model.id() == null || !seen.add(model.id())) {
			return;
		}
		models.add(model);
	}

	List<ModelForListingApi> models() {
		return List.copyOf(models);
	}
}
