package dev.suprim.gateway.api;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.utils.TokenEstimator;
import dev.suprim.gateway.virtualkey.RateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagesControllerTest {

	@Test
	void propagatesOutputConfigEffort() throws Exception {
		ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
		TokenEstimator estimator = mock(TokenEstimator.class);
		when(estimator.estimateRequest(any(), any())).thenReturn(1);
		MessagesController controller = new MessagesController(
				dispatcher,
				mock(RateLimiter.class),
				estimator
		);
		MessagesRequest request = new JsonMapper().readValue(
				"""
				{
				  "model": "claude-opus-5",
				  "max_tokens": 64000,
				  "messages": [{"role": "user", "content": "Hello"}],
				  "thinking": {"type": "adaptive"},
				  "output_config": {"effort": "high"}
				}
				""",
				MessagesRequest.class
		);

		controller.messages(
				request,
				new MockHttpServletRequest(),
				new MockHttpServletResponse()
		);

		ArgumentCaptor<InternalRequest> internalRequest = ArgumentCaptor.forClass(
				InternalRequest.class
		);
		verify(dispatcher).dispatch(
				any(),
				internalRequest.capture(),
				any(),
				any()
		);
		assertEquals("adaptive", internalRequest.getValue().thinking().type());
		assertEquals("high", internalRequest.getValue().effort());
	}

	@Test
	void ignoresMalformedOutputConfigEffort() throws Exception {
		ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
		TokenEstimator estimator = mock(TokenEstimator.class);
		when(estimator.estimateRequest(any(), any())).thenReturn(1);
		MessagesController controller = new MessagesController(
				dispatcher,
				mock(RateLimiter.class),
				estimator
		);
		MessagesRequest request = new MessagesRequest(
				"claude-opus-5",
				64000,
				java.util.List.of(new MessagesRequest.Message(
						"user",
						new JsonMapper().getNodeFactory().textNode("Hello")
				)),
				null,
				false,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				Map.of("output_config", Map.of("effort", " "))
		);

		controller.messages(
				request,
				new MockHttpServletRequest(),
				new MockHttpServletResponse()
		);

		ArgumentCaptor<InternalRequest> internalRequest = ArgumentCaptor.forClass(
				InternalRequest.class
		);
		verify(dispatcher).dispatch(
				any(),
				internalRequest.capture(),
				any(),
				any()
		);
		assertEquals(null, internalRequest.getValue().effort());
	}
}
