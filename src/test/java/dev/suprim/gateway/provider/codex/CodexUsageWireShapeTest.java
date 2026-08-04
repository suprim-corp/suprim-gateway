package dev.suprim.gateway.provider.codex;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The providers page reads these key names directly, and the records are annotated with Jackson 2's
 * {@code @JsonInclude} while Spring serializes them with Jackson 3. This pins the serialized shape
 * so a mapper or annotation change cannot silently start emitting nulls the page would render as
 * empty figures.
 */
class CodexUsageWireShapeTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void rejectionCarriesOnlyItsOwnFields() {
		assertEquals(
				"{\"message\":\"Usage unavailable (401)\",\"unauthorized\":true}",
				mapper.writeValueAsString(CodexUsage.rejected("Usage unavailable (401)"))
		);
	}

	@Test
	void absentFiguresAreOmittedRatherThanSentAsNull() {
		assertEquals(
				"{\"plan\":\"pro\"}",
				mapper.writeValueAsString(CodexUsage.builder().plan("pro").build())
		);
	}

	@Test
	void windowsSerializeUnderTheNamesThePageReads() {
		CodexUsage usage = CodexUsage.builder()
		                             .plan("pro")
		                             .limitReached(false)
		                             .session(CodexUsage.Window.builder()
		                                                       .usedPercent(40)
		                                                       .resetAt("2026-07-28T00:00:00Z")
		                                                       .build())
		                             .weekly(CodexUsage.Window.builder()
		                                                      .usedPercent(12)
		                                                      .build())
		                             .resetCredits(3)
		                             .build();

		assertEquals(
				"{\"plan\":\"pro\",\"limitReached\":false," +
				"\"session\":{\"usedPercent\":40,\"resetAt\":\"2026-07-28T00:00:00Z\"}," +
				"\"weekly\":{\"usedPercent\":12}," +
				"\"resetCredits\":3}",
				mapper.writeValueAsString(usage)
		);
	}
}
