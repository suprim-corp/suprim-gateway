package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Capability fields {@code ListAvailableModels} reports and {@code listModels} passes through.
 * The response bodies are trimmed from live captures.
 */
class KiroAuthManagerModelCapabilitiesTest {

	@TempDir
	Path tempDir;

	private KiroAuthManager authManager;
	private ProxyChain proxyChain;

	@BeforeEach
	void setUp() {
		AppConfig config = mock(AppConfig.class);
		when(config.apiRegion()).thenReturn("us-east-1");
		when(config.disabledModelsSet()).thenReturn(Set.of());
		proxyChain = mock(ProxyChain.class);
		authManager = new KiroAuthManager(
				config,
				new CredentialStore(tempDir.resolve("creds.json")),
				proxyChain
		);
	}

	@Test
	void listModels_carriesInputTypesCachingAndTokenLimits() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"claude-sonnet-5","modelName":"Claude Sonnet 5",
				"rateMultiplier":1.0,"rateUnit":"Credit",
				"supportedInputTypes":["TEXT","IMAGE"],
				"promptCaching":{"supportsPromptCaching":true},
				"tokenLimits":{"maxInputTokens":1000000,"maxOutputTokens":64000}}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals("claude-sonnet-5", model.get("id"));
		assertEquals("Claude Sonnet 5", model.get("name"));
		assertEquals(true, model.get("supportsImages"));
		assertEquals(true, model.get("supportsPromptCaching"));
		assertEquals(1000000, model.get("maxInputTokens"));
		assertEquals(64000, model.get("maxOutputTokens"));
	}

	@Test
	void listModels_textOnlyModel_reportsImagesUnsupported() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"glm-5","supportedInputTypes":["TEXT"],
				"promptCaching":{"supportsPromptCaching":false}}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals(false, model.get("supportsImages"));
		assertEquals(false, model.get("supportsPromptCaching"));
	}

	/** Claude models nest the effort enum under {@code output_config}. */
	@Test
	void listModels_readsEffortLevelsFromOutputConfigSchema() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"claude-opus-5","additionalModelRequestFieldsSchema":
				{"type":"object","properties":{"output_config":{"type":"object","properties":
				{"effort":{"type":"string","default":"high",
				"enum":["low","medium","high","xhigh","max"]}}}}}}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals(
				List.of("low", "medium", "high", "xhigh", "max"),
				model.get("effortLevels")
		);
		assertEquals("high", model.get("defaultEffort"));
	}

	/** GPT models nest the same enum under {@code reasoning} instead. */
	@Test
	void listModels_readsEffortLevelsFromReasoningSchema() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"gpt-5.6-sol","additionalModelRequestFieldsSchema":
				{"type":"object","properties":{"reasoning":{"type":"object","properties":
				{"effort":{"type":"string","default":"high",
				"enum":["none","low","medium","high","xhigh","max"]},
				"mode":{"type":"string","default":"standard"}}}}}}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals(
				List.of("none", "low", "medium", "high", "xhigh", "max"),
				model.get("effortLevels")
		);
		assertEquals("high", model.get("defaultEffort"));
	}

	@Test
	void listModels_modelWithoutSchema_reportsNoEffortLevels() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"claude-sonnet-4","additionalModelRequestFieldsSchema":null,
				"supportedInputTypes":["TEXT","IMAGE"]}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertFalse(model.containsKey("effortLevels"));
		assertFalse(model.containsKey("defaultEffort"));
	}

	/**
	 * An upstream that reports no capabilities at all must not gain invented keys — absent has
	 * to stay distinguishable from unsupported downstream.
	 */
	@Test
	void listModels_modelWithoutCapabilities_omitsThoseKeys() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"some-future-model","modelName":"Future"}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals("some-future-model", model.get("id"));
		assertFalse(model.containsKey("supportsImages"));
		assertFalse(model.containsKey("supportsPromptCaching"));
		assertFalse(model.containsKey("maxInputTokens"));
	}

	/** The dotted-version rename must not drop the capability fields along the way. */
	@Test
	void listModels_hyphenatesDottedVersionAndKeepsCapabilities() throws Exception {
		stubResponse("""
				{"models":[{"modelId":"claude-sonnet-4.5","supportedInputTypes":["TEXT","IMAGE"],
				"tokenLimits":{"maxInputTokens":200000,"maxOutputTokens":64000}}]}
				""");

		Map<String, Object> model = listModels().getFirst();

		assertEquals("claude-sonnet-4-5", model.get("id"));
		assertEquals(true, model.get("supportsImages"));
		assertEquals(200000, model.get("maxInputTokens"));
	}

	private List<Map<String, Object>> listModels() throws Exception {
		return authManager.listModels(
				StoredAccount.builder()
				             .name("test")
				             .provider("KIRO")
				             .authType("api_key")
				             .accessToken("test-key")
				             .build()
		);
	}

	@SuppressWarnings("unchecked")
	private void stubResponse(String body) throws Exception {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(body);
		when(proxyChain.send(any(HttpRequest.class))).thenReturn(response);
	}
}
