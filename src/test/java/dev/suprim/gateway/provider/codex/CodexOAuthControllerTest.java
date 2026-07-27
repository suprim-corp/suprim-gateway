package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.instants.Codex;
import dev.suprim.gateway.provider.CredentialStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodexOAuthControllerTest {

	@TempDir
	Path tempDir;

	private MockMvc mockMvc;
	private CodexLoopbackServer loopbackServer;

	@BeforeEach
	void setUp() {
		CredentialStore store = new CredentialStore(tempDir.resolve("credentials.json"));
		CodexAuthManager authManager = new CodexAuthManager(store, null);
		loopbackServer = mock(CodexLoopbackServer.class);
		mockMvc = MockMvcBuilders.standaloneSetup(
				new CodexOAuthController(authManager, loopbackServer)
		).build();
	}

	@Test
	void initiateOAuth_redirectsToOpenAiWithPkceChallenge() throws Exception {
		String location = mockMvc.perform(get("/auth/codex"))
		                        .andExpect(status().is3xxRedirection())
		                        .andReturn()
		                        .getResponse()
		                        .getRedirectedUrl();

		assertNotNull(location);
		assertTrue(location.startsWith(Codex.AUTH_URL), location);
		assertTrue(location.contains("code_challenge_method=S256"), location);
		assertTrue(location.contains("code_challenge="), location);
		assertTrue(location.contains("state="), location);
		assertTrue(location.contains("nonce="), location);
		assertTrue(location.contains("originator=" + Codex.ORIGINATOR), location);
		assertTrue(location.contains("codex_cli_simplified_flow=true"), location);
		assertFalse(location.contains("code_verifier"), "verifier must not leak: " + location);
	}

	@Test
	void initiateOAuth_sendsNonLoopbackCallersToRemoteSetup() throws Exception {
		String location = mockMvc.perform(get("/auth/codex").with(remoteHost()))
		                        .andExpect(status().is3xxRedirection())
		                        .andReturn()
		                        .getResponse()
		                        .getRedirectedUrl();

		assertNotNull(location);
		assertTrue(location.startsWith("/auth/codex/remote?state="), location);
	}

	@Test
	void agentScript_isServableForAStateFromTheRemoteRedirect() throws Exception {
		String state = stateFromRemoteRedirect();

		String script = mockMvc.perform(get("/auth/codex/agent").param("state", state))
		                      .andExpect(status().isOk())
		                      .andReturn()
		                      .getResponse()
		                      .getContentAsString();

		assertTrue(script.startsWith("#!/bin/bash"), script);
		assertTrue(script.contains("STATE='" + state + "'"), script);
		assertTrue(script.contains("CODE_VERIFIER='"), script);
		assertTrue(script.contains("PORT=" + Codex.LOOPBACK_PORT), script);
		assertTrue(script.contains("/auth/codex/exchange"), script);
		assertTrue(script.contains("open_url()"), script);
		assertTrue(script.contains("code_challenge=${CODE_CHALLENGE}"), script);
	}

	@Test
	void agentScript_refusesUnknownState() throws Exception {
		String script = mockMvc.perform(get("/auth/codex/agent").param("state", "not-issued"))
		                      .andExpect(status().isOk())
		                      .andReturn()
		                      .getResponse()
		                      .getContentAsString();

		assertTrue(script.contains("invalid or expired state"), script);
		assertFalse(script.contains("CODE_VERIFIER="), script);
	}

	@Test
	void exchange_rejectsUnknownState() throws Exception {
		String body = mockMvc.perform(
				                    post("/auth/codex/exchange")
						                    .contentType("application/json")
						                    .content("{\"code\":\"abc\",\"state\":\"not-issued\"}")
		                    )
		                    .andExpect(status().isOk())
		                    .andReturn()
		                    .getResponse()
		                    .getContentAsString();

		assertTrue(body.contains("invalid or expired state"), body);
	}

	@Test
	void exchange_rejectsMissingFields() throws Exception {
		String body = mockMvc.perform(
				                    post("/auth/codex/exchange")
						                    .contentType("application/json")
						                    .content("{\"code\":\"abc\"}")
		                    )
		                    .andExpect(status().isOk())
		                    .andReturn()
		                    .getResponse()
		                    .getContentAsString();

		assertTrue(body.contains("missing fields"), body);
	}

	@Test
	void generateState_issuesAStateUsableByTheAgentScript() throws Exception {
		String response = mockMvc.perform(post("/auth/codex/state"))
		                        .andExpect(status().isOk())
		                        .andReturn()
		                        .getResponse()
		                        .getContentAsString();

		String state = response.replaceAll(".*\"state\"\\s*:\\s*\"([^\"]+)\".*", "$1");
		assertFalse(state.isEmpty());

		String script = mockMvc.perform(get("/auth/codex/agent").param("state", state))
		                      .andExpect(status().isOk())
		                      .andReturn()
		                      .getResponse()
		                      .getContentAsString();
		assertTrue(script.startsWith("#!/bin/bash"), script);
	}

	@Test
	void deviceExchange_storesTokensAndReportsEmail() throws Exception {
		String body = mockMvc.perform(
				                    post("/auth/codex/device-exchange")
						                    .contentType("application/json")
						                    .content("""
								                    {"access_token":"at","refresh_token":"rt","expires_in":120}
								                    """)
		                    )
		                    .andExpect(status().isOk())
		                    .andReturn()
		                    .getResponse()
		                    .getContentAsString();

		assertTrue(body.contains("\"status\":\"ok\""), body);
	}

	@Test
	void deviceExchange_rejectsMissingAccessToken() throws Exception {
		String body = mockMvc.perform(
				                    post("/auth/codex/device-exchange")
						                    .contentType("application/json")
						                    .content("{\"refresh_token\":\"rt\"}")
		                    )
		                    .andExpect(status().isOk())
		                    .andReturn()
		                    .getResponse()
		                    .getContentAsString();

		assertTrue(body.contains("missing access_token"), body);
	}

	private String stateFromRemoteRedirect() throws Exception {
		MvcResult result = mockMvc.perform(get("/auth/codex").with(remoteHost()))
		                          .andReturn();
		String location = result.getResponse().getRedirectedUrl();
		assertNotNull(location);
		return URLDecoder.decode(
				location.substring(location.indexOf("state=") + "state=".length()),
				StandardCharsets.UTF_8
		);
	}

	private static RequestPostProcessor remoteHost() {
		return request -> {
			request.setServerName("gateway.example.com");
			return request;
		};
	}

	@Test
	void initiateOAuth_startsLoopbackServerForLocalFlow() throws Exception {
		mockMvc.perform(get("/auth/codex")).andExpect(status().is3xxRedirection());

		verify(loopbackServer).start(anyString(), anyString(), anyString(), any());
	}
}
