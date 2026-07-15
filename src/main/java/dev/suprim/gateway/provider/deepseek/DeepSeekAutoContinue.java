package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Detects INCOMPLETE/AUTO_CONTINUE status in DeepSeek SSE stream and
 * automatically calls /api/v0/chat/continue to splice continuation streams.
 * Max 8 continuation rounds.
 */
@Slf4j
public class DeepSeekAutoContinue {

	private static final int MAX_ROUNDS = 8;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DeepSeekHttpClient httpClient;
	private final String baseUrl;

	public DeepSeekAutoContinue(DeepSeekHttpClient httpClient, String baseUrl) {
		this.httpClient = httpClient;

		if (baseUrl.endsWith("/")) {
			this.baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		} else {
			this.baseUrl = baseUrl;
		}
	}

	@Builder
	public record Result(
			List<KiroEvent> events, String status, int responseMessageId
	) {}

	public Result process(
			InputStream originalStream,
			String chatSessionId,
			String token,
			String powHeader
	) {
		return process(originalStream, chatSessionId, token, powHeader, null);
	}

	public Result process(
			InputStream originalStream,
			String chatSessionId,
			String token,
			String powHeader,
			Consumer<KiroEvent> consumer
	) {
		String currentStatus = null;
		int messageId = 0;

		DeepSeekEventParser.Result parsed;
		if (consumer != null) {
			parsed = DeepSeekEventParser.parseStreamWithConsumer(
					originalStream, consumer
			);
		} else {
			parsed = DeepSeekEventParser.parseStream(originalStream);
		}
		List<KiroEvent> allEvents = new ArrayList<>(parsed.events());
		currentStatus = parsed.status();
		if (parsed.responseMessageId() > 0) {
			messageId = parsed.responseMessageId();
		}

		int rounds = 0;
		while (
				shouldContinue(currentStatus) && messageId > 0 &&
				rounds < MAX_ROUNDS
		) {
			rounds++;
			InputStream continuation = callContinue(
					chatSessionId,
					messageId,
					token,
					powHeader
			);
			if (continuation == null) {
				break;
			}
			DeepSeekEventParser.Result contResult;
			if (consumer != null) {
				contResult = DeepSeekEventParser.parseStreamWithConsumer(
						continuation, consumer
				);
			} else {
				contResult = DeepSeekEventParser.parseStream(continuation);
			}
			allEvents.addAll(contResult.events());
			currentStatus = contResult.status();
			if (contResult.responseMessageId() > 0) {
				messageId = contResult.responseMessageId();
			}
		}

		return Result.builder()
		             .events(allEvents)
		             .status(currentStatus)
		             .responseMessageId(messageId)
		             .build();
	}

	private boolean shouldContinue(String status) {
		return "INCOMPLETE".equals(status) || "AUTO_CONTINUE".equals(status);
	}

	private InputStream callContinue(
			String chatSessionId,
			int messageId,
			String token,
			String powHeader
	) {
		ObjectNode body = JSON.createObjectNode();
		body.put("chat_session_id", chatSessionId);
		body.put("message_id", messageId);

		Request request = httpClient.buildPostRequest(
				baseUrl + "/api/v0/chat/continue",
				body.toString(),
				token,
				powHeader
		);

		try {
			Response response = httpClient.execute(request);
			if (!response.isSuccessful()) {
				response.close();
				log.warn("Continue call failed: HTTP {}", response.code());
				return null;
			}
			if (response.body() == null) {
				response.close();
				return null;
			}
			return response.body().byteStream();
		} catch (Exception e) {
			log.warn("Continue call exception: {}", e.getMessage());
			return null;
		}
	}
}
