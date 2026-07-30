package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class KiroHeadersTest {

	private static final String CODE_WHISPERER_URL =
			Kiro.CODEWHISPERER_HOST + Kiro.GENERATE_PATH;

	private final KiroHeaders headers = new KiroHeaders(
			mock(KiroAuthManager.class)
	);

	@Test
	void usesKiroEventStreamHeadersForEveryAuthType() {
		for (boolean apiKey : new boolean[]{false, true}) {
			Map<String, String> values = headers.build(
					"token",
					apiKey,
					CODE_WHISPERER_URL
			);

			assertEquals("application/json", values.get("Content-Type"));
			assertEquals(
					"application/vnd.amazon.eventstream",
					values.get("Accept")
			);
		}
	}

	/** Only the CodeWhisperer surface understands the target header. */
	@Test
	void sendsAmzTargetToCodeWhisperer() {
		for (boolean apiKey : new boolean[]{false, true}) {
			assertEquals(
					Kiro.AMZ_TARGET,
					headers.build("token", apiKey, CODE_WHISPERER_URL)
					       .get("x-amz-target")
			);
		}
	}

	/**
	 * The kiro.dev gateway and the Q surface reject a request carrying a target header, so it must
	 * be absent rather than empty.
	 */
	@Test
	void omitsAmzTargetOnRuntimeAndQSurfaces() {
		List<String> urls = List.of(
				Kiro.RUNTIME_HOST + Kiro.GENERATE_PATH,
				Kiro.Q_HOST + Kiro.GENERATE_PATH,
				Kiro.qHost("eu-central-1") + Kiro.GENERATE_PATH
		);

		for (String url : urls) {
			assertFalse(
					headers.build("token", false, url)
					       .containsKey("x-amz-target"),
					url
			);
		}
	}
}
