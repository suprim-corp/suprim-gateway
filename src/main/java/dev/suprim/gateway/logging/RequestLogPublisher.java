package dev.suprim.gateway.logging;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class RequestLogPublisher {

	private final ApplicationEventPublisher publisher;

	public void publish(RequestLogEvent event) {
		publisher.publishEvent(event);
	}
}
