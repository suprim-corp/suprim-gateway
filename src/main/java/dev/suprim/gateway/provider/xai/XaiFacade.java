package dev.suprim.gateway.provider.xai;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.OpenAiRelayHandler;
import dev.suprim.gateway.utils.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class XaiFacade {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final XaiAuthManager authManager;
	private final RequestLogPublisher logPublisher;
	private final OpenAiRelayHandler relayHandler;
	private final AccountRotator accountRotator;
	private final CredentialStore credentialStore;

	public void handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception {
		List<StoredAccount> accounts = credentialStore.findAllByProvider(Provider.XAI.name());
		if (accounts.isEmpty()) {
			ErrorResponse.openAi(
					httpRes,
					401,
					"xAI provider not connected. Visit /auth/xai to connect.",
					"provider_not_connected"
			);
			return;
		}

		long startTime = System.currentTimeMillis();
		int maxAttempts = accounts.size();

		ObjectNode payloadNode = MAPPER.valueToTree(request);
		if (stream && !payloadNode.has("stream_options")) {
			payloadNode.set(
					"stream_options",
					MAPPER.valueToTree(Map.of("include_usage", true))
			);
		}

		JsonNode messages = payloadNode.get("messages");
		if (messages != null && messages.isArray()) {
			for (int i = 0; i < messages.size(); i++) {
				JsonNode msg = messages.get(i);
				JsonNode content = msg.get("content");
				String role;

				if (msg.has("role")) {
					role = msg.get("role").asString();
				} else {
					role = "?";
				}

				if (content != null && content.isString() &&
				    content.asString().isEmpty()) {
					((ObjectNode) msg).remove("content");
					content = null;
				}

				if (content != null && content.isArray()) {
					convertAnthropicImages((ObjectNode) msg, content);
//					content = msg.get("content");
				}

//				if (content == null || content.isNull()) {
//					log.debug(LogTag.XAI + "msg[{}] role={} content=null", i, role);
//				} else if (content.isArray()) {
//					log.debug(
//							LogTag.XAI + "msg[{}] role={} content=array({})",
//							i,
//							role,
//							content.size()
//					);
//				} else if (content.isString()) {
//					log.debug(
//							LogTag.XAI + "msg[{}] role={} content=text(len={})",
//							i,
//							role,
//							content.asString().length()
//					);
//				} else {
//					log.debug(
//							LogTag.XAI + "msg[{}] role={} content={}",
//							i,
//							role,
//							content.getNodeType()
//					);
//				}
			}
		}

		String payload = MAPPER.writeValueAsString(payloadNode);

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			StoredAccount account = accountRotator.next(Provider.XAI.name());
			String accessToken;
			try {
				accessToken = authManager.getAccessToken(account);
			} catch (Exception e) {
				log.error(LogTag.XAI + "Auth failed for {}: {}", account.name(), e.getMessage());
				continue;
			}

			log.info(LogTag.XAI + "Using account: {} (attempt {}/{})",
					account.name(), attempt + 1, maxAttempts);

			XaiHttpClient.XaiResponse response = XaiHttpClient.call(
					payload,
					accessToken
			);
			log.info(LogTag.XAI + "Upstream responded with status {}", response.status());

			if (response.status() == 429 || response.status() == 503) {
				log.warn(LogTag.XAI + "Account {} got {}, trying next",
						account.name(), response.status());
				try (InputStream is = response.body()) {
					is.readAllBytes();
				}
				continue;
			}

			if (response.status() != 200) {
				handleError(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, httpRes
				);
				return;
			}

			if (stream) {
				handleStream(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, format, httpRes
				);
			} else {
				handleNonStream(
						response, account.name(), model, inputTokens,
						keyId, clientIp, startTime, format, httpRes
				);
			}
			return;
		}

		ErrorResponse.openAi(httpRes, 429, "All accounts rate-limited", "rate_limit_exhausted");
	}

	private void handleError(
			XaiHttpClient.XaiResponse response,
			String accountName, String model, int inputTokens, String keyId, String clientIp,
			long startTime, HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}
		log.error(
				LogTag.XAI + "Upstream {} body: {}", response.status(),
				body.length() > 500 ? body.substring(0, 500) : body
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .accountId(accountName)
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(response.status())
		                                    .promptTokens(inputTokens)
		                                    .latencyMs(latency)
		                                    .streaming(false)
		                                    .clientIp(clientIp)
		                                    .errorMessage(body.length() >
		                                                  200 ? body.substring(
				                                    0,
				                                    200
		                                    ) : body)
		                                    .build());

		ErrorResponse.openAi(
				httpRes,
				response.status(),
				body.length() > 200 ? body.substring(0, 200) : body,
				"upstream_error"
		);
	}

	private void handleStream(
			XaiHttpClient.XaiResponse response,
			String accountName, String model, int inputTokens, String keyId, String clientIp,
			long startTime, Format format, HttpServletResponse httpRes
	) throws Exception {
		OpenAiRelayHandler.StreamResult result = relayHandler.relayStream(
				response.body(), format, model, inputTokens, startTime, httpRes
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(RequestLogEvent.builder()
		                                    .virtualKeyId(keyId)
		                                    .accountId(accountName)
		                                    .model(model)
		                                    .requestedModel(model)
		                                    .status(200)
		                                    .promptTokens(result.promptTokens())
		                                    .completionTokens(result.completionTokens())
		                                    .latencyMs(latency)
		                                    .firstTokenMs(
				                                    result.firstTokenMs() !=
				                                    null ? result.firstTokenMs()
				                                                 .intValue() : null)
		                                    .streaming(true)
		                                    .clientIp(clientIp)
		                                    .build());
	}

	private void handleNonStream(
			XaiHttpClient.XaiResponse response,
			String accountName, String model, int inputTokens, String keyId, String clientIp,
			long startTime, Format format, HttpServletResponse httpRes
	) throws Exception {
		String body;
		try (InputStream is = response.body()) {
			body = new String(is.readAllBytes());
		}

		Integer promptTokens = null;
		Integer completionTokens = null;
		Integer[] usage = relayHandler.extractUsage(body);
		if (usage != null) {
			promptTokens = usage[0];
			completionTokens = usage[1];
		}

		int inTokens = promptTokens != null ? promptTokens : inputTokens;
		int outTokens = completionTokens != null ? completionTokens : 0;

		relayHandler.writeNonStream(
				body,
				format,
				model,
				inTokens,
				outTokens,
				httpRes
		);

		int latency = (int) (System.currentTimeMillis() - startTime);
		logPublisher.publish(
				RequestLogEvent.builder()
				               .virtualKeyId(keyId)
				               .accountId(accountName)
				               .model(model)
				               .requestedModel(model)
				               .status(200)
				               .promptTokens(inTokens)
				               .completionTokens(outTokens)
				               .latencyMs(latency)
				               .streaming(false)
				               .clientIp(clientIp)
				               .build()
		);
	}

	private static void convertAnthropicImages(
			ObjectNode msg,
			JsonNode contentArray
	) {
		boolean hasAnthropicImage = false;
		for (JsonNode block : contentArray) {
			if (block.isObject() && block.has("type") &&
			    "image".equals(block.get("type").asString()) &&
			    block.has("source")) {
				hasAnthropicImage = true;
				break;
			}
		}
		if (!hasAnthropicImage) return;

		ArrayNode converted = MAPPER.createArrayNode();
		for (JsonNode block : contentArray) {
			if (!block.isObject()) {
				converted.add(block);
				continue;
			}
			String type = block.has("type") ? block.get("type").asString() : "";
			if ("image".equals(type) && block.has("source")) {
				JsonNode source = block.get("source");
				String mediaType;

				if (source.has("media_type")) {
					mediaType = source.get("media_type").asString();
				} else {
					mediaType = "image/jpeg";
				}

				String data;

				if (source.has("data")) {
					data = source.get("data").asString();
				} else {
					data = "";
				}

				ObjectNode imageUrlBlock = converted.addObject();
				imageUrlBlock.put("type", "image_url");
				ObjectNode imageUrl = imageUrlBlock.putObject("image_url");
				imageUrl.put("url", "data:" + mediaType + ";base64," + data);
			} else {
				converted.add(block);
			}
		}
		msg.set("content", converted);
	}
}
