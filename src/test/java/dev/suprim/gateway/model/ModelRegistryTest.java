package dev.suprim.gateway.model;

import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.provider.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.provider.codex.CodexAuthManager;
import dev.suprim.gateway.provider.kiro.KiroAccountModelAvailability;
import dev.suprim.gateway.provider.xai.XaiAuthManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelRegistryTest {

	@Mock
	private CredentialStore credentialStore;
	@Mock
	private KiroAccountModelAvailability kiroModelAvailability;
	@Mock
	private AntigravityAuthManager antigravityAuthManager;
	@Mock
	private XaiAuthManager xaiAuthManager;
	@Mock
	private CodexAuthManager codexAuthManager;

	private ModelRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new ModelRegistry(
				new ModelListingCollector(
						credentialStore,
						kiroModelAvailability,
						antigravityAuthManager,
						xaiAuthManager,
						codexAuthManager
				),
				new ProviderModelCatalog(
						kiroModelAvailability,
						antigravityAuthManager,
						xaiAuthManager,
						codexAuthManager
				)
		);
	}

	@Test
	void testRefreshCache_AntigravityWithoutDisplayName_doesNotThrowNpe() throws Exception {
		StoredAccount agAccount = StoredAccount.builder()
		                                       .provider(Provider.ANTIGRAVITY.name())
		                                       .name("test-ag")
		                                       .authType("oauth")
		                                       .build();

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(List.of(agAccount));

		Map<String, Object> model1 = new HashMap<>();
		model1.put("id", "gemini-2.5-pro");
		// displayName is null / omitted

		Map<String, Object> model2 = new HashMap<>();
		model2.put("id", "gemini-2.5-flash");
		model2.put("displayName", "Gemini 2.5 Flash");

		when(antigravityAuthManager.listModels(agAccount)).thenReturn(List.of(model1, model2));

		assertDoesNotThrow(() -> registry.refreshCache());

		List<ModelForListingApi> models = registry.getAllModelsForApi();
		assertEquals(2, models.size());
		assertEquals("ag/gemini-2.5-pro", models.get(0).id());
		assertEquals("Antigravity | gemini-2.5-pro", models.get(0).displayName());
		assertEquals("ag/gemini-2.5-flash", models.get(1).id());
		assertEquals("Antigravity | Gemini 2.5 Flash", models.get(1).displayName());
	}

	@Test
	void testRefreshCache_AntigravityFailure_usesNextAccount() throws Exception {
		StoredAccount firstAccount = StoredAccount.builder()
		                                          .provider(Provider.ANTIGRAVITY.name())
		                                          .name("first")
		                                          .build();
		StoredAccount secondAccount = StoredAccount.builder()
		                                           .provider(Provider.ANTIGRAVITY.name())
		                                           .name("second")
		                                           .build();
		Map<String, Object> model = Map.of("id", "gemini-2.5-pro");

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(List.of(firstAccount, secondAccount));
		when(antigravityAuthManager.listModels(firstAccount)).thenThrow(new NullPointerException());
		when(antigravityAuthManager.listModels(secondAccount)).thenReturn(List.of(model));

		registry.refreshCache();

		assertEquals(List.of("ag/gemini-2.5-pro"), registry.getAllModelsForApi().stream().map(ModelForListingApi::id).toList());
	}

	@Test
	void testRefreshCache_skipsAccountsWithNullOrUnknownProvider() {
		StoredAccount noProvider = StoredAccount.builder()
		                                        .name("legacy")
		                                        .build();
		StoredAccount unknownProvider = StoredAccount.builder()
		                                             .provider("SOME_FUTURE_PROVIDER")
		                                             .name("future")
		                                             .build();

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(
				List.of(noProvider, unknownProvider)
		);

		assertDoesNotThrow(() -> registry.refreshCache());
		assertTrue(registry.getAllModelsForApi().isEmpty());
	}

	@Test
	void testRefreshCache_AntigravityCapabilities_carriedIntoListing() throws Exception {
		StoredAccount agAccount = StoredAccount.builder()
		                                       .provider(Provider.ANTIGRAVITY.name())
		                                       .name("test-ag")
		                                       .build();

		Map<String, Object> model = new HashMap<>();
		model.put("id", "gemini-3.1-pro-low");
		model.put("supportsImages", true);
		model.put("supportsVideo", true);
		model.put("supportsPdf", true);
		model.put("supportsAudio", true);
		model.put("supportsThinking", true);
		model.put("thinkingBudget", 1001);
		model.put("minThinkingBudget", 128);
		model.put("maxInputTokens", 1048576);
		model.put("maxOutputTokens", 65535);

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(List.of(agAccount));
		when(antigravityAuthManager.listModels(agAccount)).thenReturn(List.of(model));

		registry.refreshCache();

		ModelForListingApi listed = registry.getAllModelsForApi().getFirst();
		assertEquals(1048576, listed.maxInputTokens());
		assertEquals(65535, listed.maxOutputTokens());
		ModelCapabilities capabilities = listed.capabilities();
		assertTrue(capabilities.imageInput().supported());
		assertTrue(capabilities.videoInput().supported());
		assertTrue(capabilities.pdfInput().supported());
		assertTrue(capabilities.audioInput().supported());
		assertTrue(capabilities.thinking().supported());
		assertEquals(1001, capabilities.thinking().budgetTokens());
		assertEquals(128, capabilities.thinking().minBudgetTokens());
	}

	@Test
	void testRefreshCache_providerReportsNoCapabilities_omitsThemEntirely() throws Exception {
		StoredAccount agAccount = StoredAccount.builder()
		                                       .provider(Provider.ANTIGRAVITY.name())
		                                       .name("test-ag")
		                                       .build();

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(List.of(agAccount));
		when(antigravityAuthManager.listModels(agAccount)).thenReturn(
				List.of(Map.of("id", "chat_20706"))
		);

		registry.refreshCache();

		ModelForListingApi listed = registry.getAllModelsForApi().getFirst();
		assertNull(listed.capabilities());
		assertNull(listed.maxInputTokens());
		assertNull(listed.maxOutputTokens());
	}

	/**
	 * A model the upstream says is text-only must serialize as supported=false, not as an
	 * absent field, so a client can tell "no images" apart from "unknown".
	 */
	@Test
	void testRefreshCache_unsupportedModality_reportedAsFalseNotAbsent() throws Exception {
		StoredAccount agAccount = StoredAccount.builder()
		                                       .provider(Provider.ANTIGRAVITY.name())
		                                       .name("test-ag")
		                                       .build();

		Map<String, Object> model = new HashMap<>();
		model.put("id", "gpt-oss-120b-medium");
		model.put("supportsImages", false);

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of());
		when(credentialStore.load()).thenReturn(List.of(agAccount));
		when(antigravityAuthManager.listModels(agAccount)).thenReturn(List.of(model));

		registry.refreshCache();

		ModelCapabilities capabilities =
				registry.getAllModelsForApi().getFirst().capabilities();
		assertNotNull(capabilities.imageInput());
		assertFalse(capabilities.imageInput().supported());
		assertNull(capabilities.videoInput());
	}

	@Test
	void testRefreshCache_KiroCapabilities_readFromAvailabilityCache() {
		Map<String, Object> details = new HashMap<>();
		details.put("id", "claude-opus-5");
		details.put("supportsImages", true);
		details.put("supportsPromptCaching", true);
		details.put("maxInputTokens", 1000000);
		details.put("maxOutputTokens", 128000);
		details.put("effortLevels", List.of("low", "medium", "high", "xhigh", "max"));
		details.put("defaultEffort", "high");

		when(kiroModelAvailability.availableModels()).thenReturn(Set.of("claude-opus-5"));
		when(kiroModelAvailability.modelDetails("claude-opus-5")).thenReturn(details);
		when(credentialStore.load()).thenReturn(List.of());

		registry.refreshCache();

		ModelForListingApi listed = registry.getAllModelsForApi().getFirst();
		assertEquals(1000000, listed.maxInputTokens());
		ModelCapabilities capabilities = listed.capabilities();
		assertTrue(capabilities.imageInput().supported());
		assertTrue(capabilities.promptCaching().supported());
		assertTrue(capabilities.effort().supported());
		assertEquals(
				List.of("low", "medium", "high", "xhigh", "max"),
				capabilities.effort().levels()
		);
		assertEquals("high", capabilities.effort().defaultLevel());
		assertNull(capabilities.thinking());
	}

	@Test
	void testGetModelsForProvider_AntigravityWithoutDisplayName_returnsModels() throws Exception {
		StoredAccount agAccount = StoredAccount.builder()
		                                       .provider(Provider.ANTIGRAVITY.name())
		                                       .name("test-ag")
		                                       .build();

		Map<String, Object> model = new HashMap<>();
		model.put("id", "gemini-2.5-pro");
		model.put("quota", 80);

		when(antigravityAuthManager.listModels(agAccount)).thenReturn(List.of(model));

		List<ModelInfo> result = registry.getModelsForProvider(agAccount);
		assertEquals(1, result.size());
		assertEquals("ag/gemini-2.5-pro", result.get(0).id());
		assertEquals(80, result.get(0).quota());
	}
}
