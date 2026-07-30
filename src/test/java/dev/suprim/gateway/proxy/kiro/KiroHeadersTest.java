package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class KiroHeadersTest {

	@Test
	void usesKiroEventStreamHeadersForEveryAuthType() {
		KiroHeaders headers = new KiroHeaders(mock(KiroAuthManager.class));

		for (boolean apiKey : new boolean[]{false, true}) {
			Map<String, String> values = headers.build("token", apiKey);

			assertEquals("application/json", values.get("Content-Type"));
			assertEquals(
					"application/vnd.amazon.eventstream",
					values.get("Accept")
			);
		}
	}
}
