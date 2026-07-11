package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.provider.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class XaiOAuthControllerTest {

	@TempDir
	Path tempDir;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		Path storePath = tempDir.resolve("credentials.json");
		CredentialStore store = new CredentialStore(storePath);
		XaiAuthManager authManager = new XaiAuthManager(store);
		XaiLoopbackServer loopbackServer = mock(XaiLoopbackServer.class);
		XaiOAuthController controller = new XaiOAuthController(authManager, loopbackServer);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void initiateOAuth_redirectsToXai() throws Exception {
		mockMvc.perform(get("/auth/xai"))
		       .andExpect(status().is3xxRedirection())
		       .andExpect(redirectedUrlPattern("https://auth.x.ai/oauth2/authorize*"));
	}

	@Test
	void initiateOAuth_includesRequiredParams() throws Exception {
		String redirectUrl = mockMvc.perform(get("/auth/xai"))
		                            .andReturn()
		                            .getResponse()
		                            .getRedirectedUrl();

		assertNotNull(redirectUrl);
		assertTrue(redirectUrl.contains("client_id="));
		assertTrue(redirectUrl.contains("redirect_uri="));
		assertTrue(redirectUrl.contains("code_challenge="));
		assertTrue(redirectUrl.contains("code_challenge_method=S256"));
		assertTrue(redirectUrl.contains("scope="));
		assertTrue(redirectUrl.contains("response_type=code"));
		assertTrue(redirectUrl.contains("state="));
		assertTrue(redirectUrl.contains("nonce="));
		assertTrue(redirectUrl.contains("plan=generic"));
		assertTrue(redirectUrl.contains("referrer=cli-proxy-api"));
	}

	@Test
	void initiateOAuth_redirectUriIsFixedLoopback() throws Exception {
		String redirectUrl = mockMvc.perform(get("/auth/xai"))
		                            .andReturn()
		                            .getResponse()
		                            .getRedirectedUrl();

		assertNotNull(redirectUrl);
		assertTrue(redirectUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A56121%2Fcallback"));
	}
}
