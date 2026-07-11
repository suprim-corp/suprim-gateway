package dev.suprim.gateway.proxy;

import dev.suprim.gateway.api.request.MessagesRequest;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.model.ModelResolver;
import dev.suprim.gateway.provider.kiro.utils.ToolConverter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class PayloadBuilder {

	private static final int MAX_PAYLOAD_BYTES = 900_000;
	private static final int MAX_TOOL_RESULT_CONTENT_LEN = 4000;
	private static final Logger log = LoggerFactory.getLogger(PayloadBuilder.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final ModelResolver modelResolver;

	public String buildOpenAiPayload(
			InternalRequest request,
			KiroAuthManager auth
	) throws Exception {
		List<Message> messages = request.messages() != null
				? new ArrayList<>(request.messages())
				: new ArrayList<>();
		String model = request.model();
		List<Tool> tools = request.tools();

		return buildKiroPayload(messages, model, tools, auth);
	}

	private String buildKiroPayload(
			List<Message> messages,
			String model,
			List<Tool> tools,
			KiroAuthManager auth
	) throws Exception {
		String modelId = modelResolver.resolve(model);

		// Separate system messages from conversation
		StringBuilder systemPrompt = new StringBuilder();
		List<Message> nonSystemMessages = new ArrayList<>();

		for (Message msg : messages) {
			if ("system".equals(msg.role())) {
				String text = ContentExtractor.fromMessage(msg);
				if (text != null && !text.isEmpty()) {
					if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
					systemPrompt.append(text);
				}
			} else {
				nonSystemMessages.add(msg);
			}
		}

		// Build history
		ArrayNode history = mapper.createArrayNode();
		String currentContent = "";
		List<Message> currentToolResults = null;

		// System prompt → priming pair at history start
		if (!systemPrompt.isEmpty()) {
			ObjectNode primingUser = mapper.createObjectNode();
			ObjectNode primingUserMsg = primingUser.putObject("userInputMessage");
			primingUserMsg.put("content", systemPrompt.toString().trim());
			primingUserMsg.put("modelId", modelId);
			primingUserMsg.put("origin", "AI_EDITOR");
			history.add(primingUser);

			ObjectNode primingAssistant = mapper.createObjectNode();
			ObjectNode primingAssistantMsg = primingAssistant.putObject(
					"assistantResponseMessage");
			primingAssistantMsg.put(
					"content",
					"I will follow these instructions."
			);
			history.add(primingAssistant);
		}

		List<ContentExtractor.KiroImage> currentImages = List.of();

		for (int i = 0; i < nonSystemMessages.size(); i++) {
			Message msg = nonSystemMessages.get(i);
			String role = msg.role();
			boolean isLast = (i == nonSystemMessages.size() - 1);

			if ("user".equals(role)) {
				String content = ContentExtractor.fromMessage(msg);
				List<ContentExtractor.KiroImage> images = ContentExtractor.extractImages(
						msg);
				if (isLast) {
					currentContent = content != null ? content : "";
					currentImages = images;
				} else {
					ObjectNode entry = mapper.createObjectNode();
					ObjectNode userMsg = entry.putObject("userInputMessage");
					userMsg.put(
							"content",
							content != null &&
							!content.isEmpty() ? content : "."
					);
					userMsg.put("modelId", modelId);
					userMsg.put("origin", "AI_EDITOR");
					appendImages(userMsg, images);
					history.add(entry);
				}
			} else if ("assistant".equals(role)) {
				String content = ContentExtractor.fromMessage(msg);
				ObjectNode entry = mapper.createObjectNode();
				ObjectNode assistantMsg = entry.putObject(
						"assistantResponseMessage");
				assistantMsg.put("content", content != null ? content : "");

				List<Message.ToolCall> toolCalls = msg.toolCalls();
				if (toolCalls != null && !toolCalls.isEmpty()) {
					ArrayNode toolUsesNode = assistantMsg.putArray("toolUses");
					for (Message.ToolCall tc : toolCalls) {
						ObjectNode tuNode = toolUsesNode.addObject();
						tuNode.put(
								"toolUseId",
								Optional.ofNullable(tc.id()).orElse("")
						);
						Message.Function fn = tc.function();
						tuNode.put(
								"name",
								fn != null ? Optional.ofNullable(fn.name())
								                     .orElse("") : ""
						);
						if (fn != null && fn.arguments() != null) {
							try {
								tuNode.set(
										"input",
										mapper.readTree(fn.arguments())
								);
							} catch (Exception e) {
								ObjectNode fallback = mapper.createObjectNode();
								fallback.put("input", fn.arguments());
								tuNode.set("input", fallback);
							}
						} else {
							tuNode.set("input", mapper.createObjectNode());
						}
					}
				}
				history.add(entry);
			} else if ("tool".equals(role)) {
				if (currentToolResults == null)
					currentToolResults = new ArrayList<>();
				currentToolResults.add(msg);

				int nextIdx = i + 1;
				boolean nextIsTool = nextIdx < nonSystemMessages.size()
				                     && "tool".equals(
						nonSystemMessages.get(nextIdx).role()
				);

				if (!nextIsTool && !isLast) {
					ObjectNode entry = mapper.createObjectNode();
					ObjectNode userMsg = entry.putObject("userInputMessage");
					userMsg.put("content", ".");
					userMsg.put("modelId", modelId);
					userMsg.put("origin", "AI_EDITOR");
					ObjectNode ctx = userMsg.putObject("userInputMessageContext");
					ArrayNode resultsNode = ctx.putArray("toolResults");
					for (Message tr : currentToolResults) {
						ObjectNode resultObj = resultsNode.addObject();
						resultObj.put(
								"toolUseId",
								Optional.ofNullable(tr.toolCallId()).orElse("")
						);
						ArrayNode contentArr = resultObj.putArray("content");
						ObjectNode textObj = contentArr.addObject();
						textObj.put(
								"text",
								truncate(
										ContentExtractor.fromMessage(tr)
								)
						);
						resultObj.put("status", "success");
					}
					history.add(entry);
					currentToolResults = null;
				}
			}
		}

		// Current message content
		if (currentContent.isEmpty()) {
			if (currentToolResults != null && !currentToolResults.isEmpty()) {
				currentContent = buildToolResultsContinuation(currentToolResults);
			} else if (!currentImages.isEmpty()) {
				currentContent = ".";
			} else {
				currentContent = ".";
			}
		}

		// Build payload
		ObjectNode root = mapper.createObjectNode();
		ObjectNode conversationState = root.putObject("conversationState");
		conversationState.put("chatTriggerType", "MANUAL");
		conversationState.put("conversationId", UUID.randomUUID().toString());

		ObjectNode currentMessage = conversationState.putObject("currentMessage");
		ObjectNode userInputMessage = currentMessage.putObject(
				"userInputMessage");
		userInputMessage.put("content", currentContent);
		userInputMessage.put("modelId", modelId);
		userInputMessage.put("origin", "AI_EDITOR");
		appendImages(userInputMessage, currentImages);

		// Attach tools and current tool results to currentMessage context
		boolean hasTools = tools != null && !tools.isEmpty();
		boolean hasCurrentToolResults =
				currentToolResults != null && !currentToolResults.isEmpty();

		if (hasTools || hasCurrentToolResults) {
			ObjectNode ctx = userInputMessage.putObject(
					"userInputMessageContext"
			);
			if (hasTools) {
				ArrayNode toolsArr = ctx.putArray("tools");
				for (JsonNode kiroToolNode : mapper.valueToTree(ToolConverter.convert(tools))) {
					toolsArr.add(kiroToolNode);
				}
			}
			if (hasCurrentToolResults) {
				ArrayNode resultsNode = ctx.putArray("toolResults");
				for (Message tr : currentToolResults) {
					ObjectNode resultObj = resultsNode.addObject();
					resultObj.put(
							"toolUseId",
							Optional.ofNullable(tr.toolCallId()).orElse("")
					);
					ArrayNode contentArr = resultObj.putArray("content");
					ObjectNode textObj = contentArr.addObject();
					textObj.put(
							"text",
							truncate(
									ContentExtractor.fromMessage(tr)
							)
					);
					resultObj.put("status", "success");
				}
			}
		}

		if (!history.isEmpty()) {
			conversationState.set("history", history);
		}

		if (auth.getProfileArn() != null) {
			root.put("profileArn", auth.getProfileArn());
		}

		// Truncate history if payload too large
		String json = mapper.writeValueAsString(root);
		log.debug(
				"[Payload] size={}, history={}, hasTools={}, hasToolResults={}",
				json.length(),
				history.size(),
				hasTools,
				hasCurrentToolResults
		);
		while (json.length() > MAX_PAYLOAD_BYTES && history.size() > 2) {
			int removeIdx =
					!systemPrompt.isEmpty() && history.size() > 2 ? 2 : 0;
			history.remove(removeIdx);
			if (history.isEmpty()) conversationState.remove("history");
			json = mapper.writeValueAsString(root);
		}

		return json;
	}

	public List<Message> convertResponsesInput(Object input) {
		return ResponsesInputConverter.convert(input);
	}

	public List<Message> convertAnthropicMessages(MessagesRequest request) {
		List<Message> result = new ArrayList<>();

		JsonNode sys = request.system();
		if (sys != null) {
			String systemText;
			if (sys.isString()) {
				systemText = sys.stringValue();
			} else if (sys.isArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode item : sys) {
					if (item.has("text")) sb.append(item.get("text")
					                                    .stringValue());
				}
				systemText = sb.toString();
			} else {
				systemText = sys.toString();
			}
			result.add(Message.of("system", systemText));
		}

		if (request.messages() != null) {
			for (MessagesRequest.Message msg : request.messages()) {
				String role = msg.role();
				JsonNode content = msg.content();
				if (content == null) {
					result.add(Message.of(role, ""));
				} else if (content.isString()) {
					result.add(Message.of(role, content.stringValue()));
				} else if (content.isArray() && hasImageBlock(content)) {
					List<Object> parts = mapper.convertValue(
							content,
							new TypeReference<>() {}
					);
					result.add(Message.of(role, parts));
				} else if (content.isArray()) {
					StringBuilder sb = new StringBuilder();
					for (JsonNode item : content) {
						if (item.has("type") && "text".equals(item.get("type")
						                                          .stringValue()))
							sb.append(item.get("text").stringValue());
					}
					result.add(Message.of(role, sb.toString()));
				} else {
					result.add(Message.of(role, content.toString()));
				}
			}
		}

		return result;
	}

	private boolean hasImageBlock(JsonNode contentArray) {
		for (JsonNode item : contentArray) {
			if (item.has("type") && "image".equals(item.get("type")
			                                           .stringValue()))
				return true;
		}
		return false;
	}

	private String buildToolResultsContinuation(List<Message> toolResults) {
		StringBuilder sb = new StringBuilder("Tool results:\n\n");
		for (Message tr : toolResults) {
			String content = ContentExtractor.fromMessage(tr);
			if (content != null && !content.isEmpty()) {
				sb.append(content).append("\n\n");
			}
		}
		return truncate(sb.toString().trim());
	}

	private void appendImages(
			ObjectNode userMsg,
			List<ContentExtractor.KiroImage> images
	) {
		if (images == null || images.isEmpty()) return;
		ArrayNode imagesNode = userMsg.putArray("images");
		for (ContentExtractor.KiroImage img : images) {
			ObjectNode imgNode = imagesNode.addObject();
			imgNode.put("format", img.format());
			ObjectNode source = imgNode.putObject("source");
			source.put("bytes", img.bytes());
		}
	}

	private static String str(Object obj) {
		return obj != null ? obj.toString() : "";
	}

	private static String truncate(String s) {
		if (s == null) return "";
		return s.length() <=
		       PayloadBuilder.MAX_TOOL_RESULT_CONTENT_LEN ? s : s.substring(
				0,
				PayloadBuilder.MAX_TOOL_RESULT_CONTENT_LEN
		);
	}
}
