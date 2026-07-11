package dev.suprim.gateway.proxy;

import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.proxy.KiroHttpClient.KiroResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class UpstreamCaller {

	private static final Logger log = LoggerFactory.getLogger(UpstreamCaller.class);
	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;

	public KiroResponse call(
			Map<String, Object> openAiRequest,
			boolean stream
	) throws Exception {
		String payload = payloadBuilder.buildOpenAiPayload(openAiRequest, auth);
		log.debug("[Upstream] payload: {}", payload.length() > 3000 ? payload.substring(0, 3000) : payload);
		String url = kiroClient.getGenerateUrl();
		return kiroClient.request("POST", url, payload, stream);
	}
}
