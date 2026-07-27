package dev.suprim.gateway.provider.antigravity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response shape for {@code /v1internal:onboardUser}, a long-running operation that
 * carries the provisioned project once {@code done} flips to true.
 */
final class OnboardUser {

	private OnboardUser() {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Operation(
			boolean done,
			Result response
	) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Result(LoadCodeAssist.Response.Project cloudaicompanionProject) {}
}
