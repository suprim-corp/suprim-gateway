package dev.suprim.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.PrintWriter;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SseHeartbeatTest {

	@Test
	void emitsHeartbeatEveryFifteenSecondsUntilClosed() throws Exception {
		SseHeartbeatScheduler scheduler = mock(SseHeartbeatScheduler.class);
		ScheduledFuture<?> future = mock(ScheduledFuture.class);
		ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
		when(scheduler.schedule(heartbeat.capture(), eq(15L))).thenAnswer(
				ignored -> future
		);
		SseHeartbeat sseHeartbeat = new SseHeartbeat(scheduler);
		MockHttpServletResponse response = new MockHttpServletResponse();

		SseHeartbeat.Session session = sseHeartbeat.open(response);
		heartbeat.getValue().run();

		assertEquals("text/event-stream; charset=utf-8", response.getContentType());
		assertEquals("no-cache", response.getHeader("Cache-Control"));
		assertEquals("no", response.getHeader("X-Accel-Buffering"));
		assertEquals(": heartbeat\n\n", response.getContentAsString());

		session.close();
		session.close();
		heartbeat.getValue().run();

		verify(future, times(1)).cancel(false);
		assertEquals(": heartbeat\n\n", response.getContentAsString());
	}

	@Test
	void serializesProviderOutputWithHeartbeat() throws Exception {
		SseHeartbeatScheduler scheduler = mock(SseHeartbeatScheduler.class);
		ScheduledFuture<?> future = mock(ScheduledFuture.class);
		ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
		when(scheduler.schedule(heartbeat.capture(), eq(15L))).thenAnswer(
				ignored -> future
		);
		MockHttpServletResponse response = new MockHttpServletResponse();

		try (SseHeartbeat.Session session = new SseHeartbeat(scheduler).open(response)) {
			PrintWriter writer = session.writer();
			writer.write("data: one\n\n");
			heartbeat.getValue().run();
			writer.write("data: two\n\n");
			writer.flush();
		}

		assertEquals(
				"data: one\n\n: heartbeat\n\ndata: two\n\n",
				response.getContentAsString()
		);
	}
}
