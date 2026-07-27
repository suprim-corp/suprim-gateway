package dev.suprim.gateway.provider.antigravity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response shape for {@code /v1internal:retrieveUserQuotaSummary}.
 * <p>
 * Quota is reported per bucket, not as a single number: each model group (Gemini, third
 * party) has both a weekly and a rolling 5-hour window, so a typical response carries four
 * buckets that refill independently.
 */
final class UserQuota {

	private UserQuota() {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Summary(List<Group> groups) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Group(
				String displayName,
				String description,
				List<Bucket> buckets
		) {}

		/**
		 * One quota window. {@code remainingFraction} is what is left, not what was used,
		 * and is absent rather than zero when the upstream does not report it.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		record Bucket(
				String bucketId,
				String displayName,
				String window,
				String resetTime,
				String description,
				Double remainingFraction
		) {

			boolean hasUsableFraction() {
				return remainingFraction != null
				       && remainingFraction >= 0
				       && remainingFraction <= 1;
			}

			int remainingPercent() {
				return (int) Math.round(remainingFraction * 100);
			}
		}
	}
}
