package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntigravityPayloadShapeTest {

	@Test
	void elidesLongStringsButKeepsStructure() {
		String base64 = "A".repeat(5000);
		String payload = """
				{"model":"gemini-2.5-flash","request":{"contents":[{"role":"user",\
				"parts":[{"inlineData":{"mimeType":"audio/wav","data":"%s"}}]}]}}\
				""".formatted(base64);

		String shape = AntigravityPayloadShape.of(payload);

		assertFalse(shape.contains(base64));
		assertTrue(shape.contains("<5000 chars>"));
		assertTrue(shape.contains("\"mimeType\":\"audio/wav\""));
		assertTrue(shape.contains("\"role\":\"user\""));
		assertTrue(shape.contains("\"model\":\"gemini-2.5-flash\""));
	}

	@Test
	void keepsShortStringsVerbatim() {
		assertTrue(
				AntigravityPayloadShape.of("{\"text\":\"say OK\"}")
				                       .contains("\"text\":\"say OK\"")
		);
	}

	@Test
	void reportsLengthWhenUnparseable() {
		assertEquals(
				"<unparseable, 3 chars>",
				AntigravityPayloadShape.of("{ x")
		);
	}

	@Test
	void handlesNullPayload() {
		assertEquals("<none>", AntigravityPayloadShape.of(null));
	}
}
