package dev.suprim.gateway.provider.antigravity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

/**
 * One account's Antigravity quota, as the providers page consumes it.
 * <p>
 * Quota is reported per window, not as a single number, so {@code buckets} carries them all.
 * Top-level {@code quota} and {@code resetTime} mirror the <em>most constrained</em> percent-based
 * bucket, since that is the one that will actually stop a request.
 * <p>
 * A lookup that failed carries neither, and {@code unauthorized} when the credential itself was
 * refused. Absent fields are omitted rather than sent as null, so an account with no readable quota
 * does not render as one reporting zero.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AntigravityQuota(
		Integer quota,
		String resetTime,
		String tier,
		List<Bucket> buckets,
		Boolean unauthorized
) {

	/**
	 * One quota window.
	 * <p>
	 * {@code quota} (percent remaining) and {@code remaining} (an absolute count) are the two arms
	 * of a {@code oneof}: a window reports one or the other, never both. A count cannot be turned
	 * into a percent because the upstream does not report the window's total.
	 */
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Bucket(
			String group,
			String label,
			Integer quota,
			Long remaining,
			String resetTime,
			String description
	) {}

	/**
	 * Nothing readable to report — an outage, an unparseable body, or no usable window.
	 */
	public static AntigravityQuota none() {
		return AntigravityQuota.builder().build();
	}

	/**
	 * The credential was refused, so the providers page can stop calling the account connected.
	 */
	public static AntigravityQuota rejected() {
		return AntigravityQuota.builder().unauthorized(true).build();
	}

	/**
	 * The same quota with a subscription tier attached; the tier comes from a separate endpoint.
	 */
	public AntigravityQuota withTier(String tier) {
		return toBuilder().tier(tier).build();
	}
}
