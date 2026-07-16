package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.suprim.gateway.provider.CredentialStore;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AntigravityOAuthControllerTest {

	@TempDir
	Path tempDir;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		Path storePath = tempDir.resolve("credentials.json");
		CredentialStore store = new CredentialStore(storePath);
		AntigravityAuthManager authManager = new AntigravityAuthManager(store, null);
		AntigravityLoopbackServer loopbackServer = mock(AntigravityLoopbackServer.class);
		AntigravityOAuthController controller = new AntigravityOAuthController(authManager, loopbackServer);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void initiateOAuth_redirectsToGoogle() throws Exception {
		mockMvc.perform(get("/auth/antigravity"))
		       .andExpect(status().is3xxRedirection())
		       .andExpect(redirectedUrlPattern("https://accounts.google.com/o/oauth2/v2/auth*"));
	}

	@Test
	void initiateOAuth_includesRequiredParams() throws Exception {
		String redirectUrl = mockMvc.perform(get("/auth/antigravity"))
		                            .andReturn()
		                            .getResponse()
		                            .getRedirectedUrl();

		assertNotNull(redirectUrl);
		assertTrue(redirectUrl.contains("client_id="));
		assertTrue(redirectUrl.contains("redirect_uri="));
		assertTrue(redirectUrl.contains("code_challenge="));
		assertTrue(redirectUrl.contains("code_challenge_method=S256"));
		assertTrue(redirectUrl.contains("scope="));
		assertTrue(redirectUrl.contains("access_type=offline"));
		assertTrue(redirectUrl.contains("response_type=code"));
	}

	@Test
	void initiateOAuth_usesFixedRedirectUri() throws Exception {
		String redirectUrl = mockMvc.perform(get("/auth/antigravity"))
		                            .andReturn()
		                            .getResponse()
		                            .getRedirectedUrl();

		assertNotNull(redirectUrl);
		String encodedRedirectUri = java.net.URLEncoder.encode(Antigravity.REDIRECT_URI, java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(redirectUrl.contains("redirect_uri=" + encodedRedirectUri));
	}
}
