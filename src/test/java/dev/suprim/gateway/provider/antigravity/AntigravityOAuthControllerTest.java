package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.suprim.gateway.provider.CredentialStore;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AntigravityOAuthControllerTest {

	@TempDir
	Path tempDir;

	private MockMvc mockMvc;
	private AntigravityAuthManager authManager;

	@BeforeEach
	void setUp() {
		Path storePath = tempDir.resolve("credentials.json");
		CredentialStore store = new CredentialStore(storePath);
		authManager = new AntigravityAuthManager(store);
		AntigravityOAuthController controller = new AntigravityOAuthController(authManager);
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

		assert redirectUrl != null;
		assertTrue(redirectUrl.contains("client_id="));
		assertTrue(redirectUrl.contains("redirect_uri="));
		assertTrue(redirectUrl.contains("code_challenge="));
		assertTrue(redirectUrl.contains("code_challenge_method=S256"));
		assertTrue(redirectUrl.contains("scope="));
		assertTrue(redirectUrl.contains("access_type=offline"));
		assertTrue(redirectUrl.contains("response_type=code"));
	}

	@Test
	void initiateOAuth_storesCodeVerifierInSession() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(get("/auth/antigravity").session(session));

		Object verifier = session.getAttribute("ag_code_verifier");
		assertNotNull(verifier);
		assertTrue(((String) verifier).length() >= 43);
	}

	@Test
	void initiateOAuth_redirectUriUsesRequestPort() throws Exception {
		String redirectUrl = mockMvc.perform(get("/auth/antigravity"))
		                            .andReturn()
		                            .getResponse()
		                            .getRedirectedUrl();

		assert redirectUrl != null;
		// MockMvc defaults to localhost:80, so redirect_uri should be http://localhost/callback/antigravity
		assertTrue(redirectUrl.contains("redirect_uri=http"));
		assertTrue(redirectUrl.contains("callback%2Fantigravity") || redirectUrl.contains("/callback/antigravity"));
	}

	private static void assertTrue(boolean condition) {
		org.junit.jupiter.api.Assertions.assertTrue(condition);
	}

	private static void assertNotNull(Object obj) {
		org.junit.jupiter.api.Assertions.assertNotNull(obj);
	}
}
