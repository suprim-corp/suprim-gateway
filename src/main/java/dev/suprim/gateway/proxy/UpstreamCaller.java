package dev.suprim.gateway.proxy;

import dev.suprim.gateway.auth.KiroAuthManager;
import dev.suprim.gateway.proxy.KiroHttpClient.KiroResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class UpstreamCaller {

	private final KiroHttpClient kiroClient;
	private final PayloadBuilder payloadBuilder;
	private final KiroAuthManager auth;

	public KiroResponse call(
			Map<String, Object> openAiRequest,
			boolean stream
	) throws Exception {
		String payload = payloadBuilder.buildOpenAiPayload(openAiRequest, auth);
		String url = kiroClient.getGenerateUrl();
		return kiroClient.request("POST", url, payload, stream);
	}
}
