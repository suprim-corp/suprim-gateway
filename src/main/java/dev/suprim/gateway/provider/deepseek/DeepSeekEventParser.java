package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses DeepSeek Web SSE stream into KiroEvents.
 * Handles content, thinking, status, fragments, response_message_id, and content_filter.
 */
@Slf4j
public class DeepSeekEventParser {

	private static final Pattern THINK_CLOSE =
			Pattern.compile("(?i)</\\s*think\\s*>");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> KNOWN_STATUSES = Set.of(
			"FINISHED", "INCOMPLETE", "AUTO_CONTINUE", "CONTENT_FILTER", "WIP"
	);

	private final StringBuilder buffer = new StringBuilder();
	private final List<KiroEvent> events = new ArrayList<>();
	private final Consumer<KiroEvent> eventConsumer;
	private final Set<Integer> thinkingFragmentIndices = new HashSet<>();
	private boolean lastFragmentIsThinking = true;
	private String status;
	private int responseMessageId;

	@Builder
	public record Result(
			List<KiroEvent> events, String status, int responseMessageId
	) {}

	public DeepSeekEventParser() {
		this.eventConsumer = null;
	}

	public DeepSeekEventParser(Consumer<KiroEvent> eventConsumer) {
		this.eventConsumer = eventConsumer;
	}

	public static Result parseStream(InputStream input) {
		DeepSeekEventParser parser = new DeepSeekEventParser();
		try {
			byte[] buf = new byte[8192];
			int read;
			while ((read = input.read(buf)) != -1) {
				parser.feed(buf, 0, read);
			}
		} catch (Exception e) {
			log.error("Error parsing stream: {}", e.getMessage());
		}
		return parser.finish();
	}

	public static Result parseStreamWithConsumer(
			InputStream input,
			Consumer<KiroEvent> consumer
	) {
		DeepSeekEventParser parser = new DeepSeekEventParser(consumer);
		try {
			byte[] buf = new byte[8192];
			int read;
			while ((read = input.read(buf)) != -1) {
				parser.feed(buf, 0, read);
			}
		} catch (Exception e) {
			log.error("Error parsing stream: {}", e.getMessage());
		}
		return parser.finish();
	}

	public void feed(byte[] data) {
		feed(data, 0, data.length);
	}

	public void feed(byte[] data, int offset, int length) {
		buffer.append(new String(data, offset, length, StandardCharsets.UTF_8));
		processBuffer();
	}

	public Result finish() {
		processBuffer();
		return Result.builder()
		             .events(List.copyOf(events))
		             .status(status)
		             .responseMessageId(responseMessageId)
		             .build();
	}

	private void processBuffer() {
		while (true) {
			int idx = buffer.indexOf("\n\n");
			if (idx < 0) {
				break;
			}
			String rawLine = buffer.substring(0, idx).trim();
			buffer.delete(0, idx + 2);
			if (!rawLine.startsWith("data:")) {
				continue;
			}
			String dataStr = rawLine.substring(5).trim();
			if (dataStr.equals("[DONE]")) {
				continue;
			}
			parseChunk(dataStr);
		}
	}

	private void parseChunk(String json) {
		JsonNode chunk;
		try {
			chunk = JSON.readTree(json);
		} catch (Exception ignored) {
			return;
		}

		observeResponseMessageId(chunk);
		detectContentFilter(chunk);

		String path = chunk.has("p") ? chunk.get("p").asString("") : "";
		JsonNode v = chunk.get("v");

		if (isStatusPath(path) && v != null && v.isString()) {
			String s = v.asString().trim().toUpperCase();
			if (KNOWN_STATUSES.contains(s)) {
				status = s;
			}
			return;
		}

		String op = chunk.has("o") ? chunk.get("o").asString("") : "";
		if ("response/fragments".equals(path) &&
		    "APPEND".equalsIgnoreCase(op) && v != null && v.isArray()) {
			parseFragments(v);
			return;
		}

		if (v == null || !v.isString()) {
			if (v != null && v.isObject()) {
				observeNestedMessageId(v);
				extractObjectFragments(v);
			}
			return;
		}

		String text = v.asString();
		if (text.isEmpty()) {
			return;
		}

		if ("response/thinking_content".equals(path)) {
			emitThinkingWithTagSplit(text);
		} else if ("response/content".equals(path)) {
			emit(KiroEvent.content(text));
		} else if (path.contains("response/fragments") && path.contains(
				"/content")) {
			int fragIndex = extractFragmentIndex(path);
			if (fragIndex >= 0 && thinkingFragmentIndices.contains(fragIndex)) {
				emit(KiroEvent.reasoning(text));
			} else if (fragIndex < 0 && lastFragmentIsThinking) {
				emit(KiroEvent.reasoning(text));
			} else {
				emit(KiroEvent.content(text));
			}
		} else if (path.isEmpty() && v.isString()) {
			if (lastFragmentIsThinking) {
				emit(KiroEvent.reasoning(text));
			} else {
				emit(KiroEvent.content(text));
			}
		}
	}

	private void parseFragments(JsonNode fragments) {
		int baseIndex = thinkingFragmentIndices.isEmpty() ? 0 :
				thinkingFragmentIndices.stream()
				                       .mapToInt(Integer::intValue)
				                       .max()
				                       .orElse(-1) + 1;

		for (int i = 0; i < fragments.size(); i++) {
			JsonNode frag = fragments.get(i);
			if (!frag.isObject()) {
				continue;
			}

			String type;
			String content;

			if (frag.has("type")) {
				type = frag.get("type")
				           .asString("")
				           .toUpperCase();
			} else {
				type = "";
			}

			if (frag.has("content")) {
				content = frag.get("content").asString();
			} else {
				content = "";
			}

			boolean isThinking =
					"THINK".equals(type) || "THINKING".equals(type);
			if (isThinking) {
				thinkingFragmentIndices.add(baseIndex + i);
			}
			lastFragmentIsThinking = isThinking;

			if (content.isEmpty()) {
				continue;
			}

			if (isThinking) {
				emit(KiroEvent.reasoning(content));
			} else {
				emit(KiroEvent.content(content));
			}
		}
	}

	private void emit(KiroEvent event) {
		events.add(event);
		if (eventConsumer != null) {
			eventConsumer.accept(event);
		}
	}

	private void emitThinkingWithTagSplit(String text) {
		Matcher matcher = THINK_CLOSE.matcher(text);
		if (!matcher.find()) {
			emit(KiroEvent.reasoning(text));
			return;
		}
		String before = text.substring(0, matcher.start());
		if (!before.isEmpty()) {
			emit(KiroEvent.reasoning(before));
		}
	}

	private void observeResponseMessageId(JsonNode chunk) {
		if (chunk.has("response_message_id")) {
			int id = chunk.get("response_message_id").asInt(0);
			if (id > 0) {
				responseMessageId = id;
			}
		}
	}

	private void observeNestedMessageId(JsonNode v) {
		JsonNode response = v.path("response");
		if (response.has("message_id")) {
			int id = response.get("message_id").asInt(0);
			if (id > 0) {
				responseMessageId = id;
			}
		}
	}

	private void extractObjectFragments(JsonNode v) {
		JsonNode response = v.path("response");
		if (response.isMissingNode()) {
			return;
		}
		JsonNode statusNode = response.path("status");
		if (!statusNode.isMissingNode() && statusNode.isString()) {
			String s = statusNode.asString("").trim().toUpperCase();
			if (KNOWN_STATUSES.contains(s)) {
				status = s;
			}
		}
		JsonNode fragments = response.path("fragments");
		if (!fragments.isMissingNode() && fragments.isArray()) {
			parseFragments(fragments);
		}
	}

	private void detectContentFilter(JsonNode chunk) {
		if (chunk.has("code")) {
			String code = chunk.get("code").asString("").trim();
			if ("content_filter".equalsIgnoreCase(code)) {
				status = "CONTENT_FILTER";
			}
		}
	}

	private boolean isStatusPath(String path) {
		return "response/status".equals(path) || "status".equals(path);
	}

	private static final Pattern FRAGMENT_INDEX_PATTERN =
			Pattern.compile("response/fragments/(\\d+)/content");

	private int extractFragmentIndex(String path) {
		Matcher matcher = FRAGMENT_INDEX_PATTERN.matcher(path);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(1));
		}
		return -1;
	}
}
