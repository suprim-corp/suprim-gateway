package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The providers page and the models dialog both read these key names directly, and the records are
 * annotated with Jackson 2's {@code @JsonInclude} while Spring serializes them with Jackson 3. This
 * pins the serialized shape so neither an annotation nor a mapper change can start emitting nulls
 * the page would render as empty figures.
 */
class AntigravityQuotaWireShapeTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void nothingReadableSerializesAsAnEmptyObject() {
		assertEquals("{}", mapper.writeValueAsString(AntigravityQuota.none()));
	}

	@Test
	void rejectionCarriesOnlyItsFlag() {
		assertEquals(
				"{\"unauthorized\":true}",
				mapper.writeValueAsString(AntigravityQuota.rejected())
		);
	}

	@Test
	void aFractionBucketOmitsTheCountArmAndViceVersa() {
		AntigravityQuota quota = AntigravityQuota.builder()
		                                        .quota(30)
		                                        .resetTime("2026-07-27T20:52:05Z")
		                                        .buckets(java.util.List.of(
				                                        AntigravityQuota.Bucket.builder()
				                                                               .group("Gemini Models")
				                                                               .label("Weekly Limit")
				                                                               .quota(30)
				                                                               .build(),
				                                        AntigravityQuota.Bucket.builder()
				                                                               .group("Gemini Models")
				                                                               .label("Credits")
				                                                               .remaining(7L)
				                                                               .build()
		                                        ))
		                                        .build();

		assertEquals(
				"{\"quota\":30,\"resetTime\":\"2026-07-27T20:52:05Z\",\"buckets\":[" +
				"{\"group\":\"Gemini Models\",\"label\":\"Weekly Limit\",\"quota\":30}," +
				"{\"group\":\"Gemini Models\",\"label\":\"Credits\",\"remaining\":7}]}",
				mapper.writeValueAsString(quota)
		);
	}

	@Test
	void theTierRidesAlongsideTheFiguresItWasStitchedOnto() {
		assertEquals(
				"{\"quota\":42,\"tier\":\"Pro\"}",
				mapper.writeValueAsString(
						AntigravityQuota.builder().quota(42).build().withTier("Pro")
				)
		);
	}
}
