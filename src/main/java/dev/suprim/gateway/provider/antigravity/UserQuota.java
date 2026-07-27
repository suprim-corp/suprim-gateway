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
		 * One quota window. Both remaining figures describe what is left, not what was
		 * used, and are absent rather than zero when the upstream does not report them.
		 * <p>
		 * {@code remainingFraction} and {@code remainingAmount} are the two arms of a
		 * {@code oneof}: a bucket reports either a fraction of its window or an absolute
		 * count of remaining units, never both. An amount cannot be turned into a percent
		 * because the window's total is not reported, so callers must handle the two
		 * shapes separately.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		record Bucket(
				String bucketId,
				String displayName,
				String window,
				String resetTime,
				String description,
				Double remainingFraction,
				Long remainingAmount
		) {

			boolean hasUsableFraction() {
				return remainingFraction != null
				       && remainingFraction >= 0
				       && remainingFraction <= 1;
			}

			boolean hasUsableAmount() {
				return remainingAmount != null && remainingAmount >= 0;
			}

			int remainingPercent() {
				return (int) Math.round(remainingFraction * 100);
			}
		}
	}
}
