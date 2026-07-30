package dev.suprim.gateway.proxy.token;

import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.Message;
import dev.suprim.gateway.proxy.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Component
public class RequestOptimizer {

	private static final Set<String> WEB_TOOLS = Set.of(
			"WebSearch", "WebFetch", "mcp__workspace__web_fetch"
	);
	private static final Set<String> EXA_TRIGGERS = Set.of(
			"mcp__exa__web_search_exa", "mcp__exa__web_fetch_exa"
	);
	private static final Set<String> TAVILY_TRIGGERS = Set.of(
			"mcp__tavily__tavily_search", "mcp__tavily__tavily_extract"
	);
	private static final Pattern BROWSER_TRIGGER = Pattern.compile("^mcp__browsermcp__.*");
	private static final Pattern CHROME_CONNECTOR = Pattern.compile("^mcp__Claude_in_Chrome__.*");

	public OptimizationResult optimize(Provider provider, InternalRequest request) {
		int before = characterCount(request);
		if (!isSupported(provider)) {
			return result(request, before, before);
		}
		try {
			List<Message> messages = optimizeMessages(request.messages());
			List<Tool> tools = deduplicateTools(request.tools());
			InternalRequest optimized = InternalRequest.builder()
			                                         .model(request.model())
			                                         .messages(messages)
			                                         .stream(request.stream())
			                                         .tools(tools)
			                                         .temperature(request.temperature())
			                                         .topP(request.topP())
			                                         .maxTokens(request.maxTokens())
			                                         .thinking(request.thinking())
			                                         .effort(request.effort())
			                                         .clientSessionId(request.clientSessionId())
			                                         .build();
			return result(optimized, before, characterCount(optimized));
		} catch (RuntimeException ignored) {
			return result(request, before, before);
		}
	}

	private static OptimizationResult result(
			InternalRequest request,
			int charactersBefore,
			int charactersAfter
	) {
		return OptimizationResult.builder()
		                         .request(request)
		                         .metrics(
				                         OptimizationMetrics.builder()
				                                            .charactersBefore(charactersBefore)
				                                            .charactersAfter(charactersAfter)
				                                            .build()
		                         )
		                         .build();
	}

	private static boolean isSupported(Provider provider) {
		return provider == Provider.KIRO || provider == Provider.CODEX ||
		       provider == Provider.ANTIGRAVITY;
	}

	private static List<Message> optimizeMessages(List<Message> source) {
		if (source == null) {
			return null;
		}
		List<Message> result = new ArrayList<>(source.size());
		for (Message message : source) {
			if (message == null || !"tool".equals(message.role()) ||
			    Boolean.TRUE.equals(message.toolError()) ||
			    !(message.content() instanceof String content)) {
				result.add(message);
				continue;
			}
			result.add(Message.builder()
			                  .role(message.role())
			                  .content(ToolResultCompressor.compress(content))
			                  .name(message.name())
			                  .toolCalls(message.toolCalls())
			                  .toolCallId(message.toolCallId())
			                  .toolError(message.toolError())
			                  .build());
		}
		return List.copyOf(result);
	}

	private static List<Tool> deduplicateTools(List<Tool> source) {
		if (source == null) {
			return null;
		}
		Set<String> names = new LinkedHashSet<>();
		for (Tool tool : source) {
			String name = toolName(tool);
			if (name != null) {
				names.add(name);
			}
		}
		boolean externalWeb = names.stream().anyMatch(EXA_TRIGGERS::contains) ||
		                      names.stream().anyMatch(TAVILY_TRIGGERS::contains);
		boolean browserMcp = names.stream().anyMatch(matches(BROWSER_TRIGGER));
		return source.stream()
		             .filter(tool -> {
			             String name = toolName(tool);
			             return name == null ||
			                    !(externalWeb && WEB_TOOLS.contains(name)) &&
			                    !(browserMcp && CHROME_CONNECTOR.matcher(name).matches());
		             })
		             .toList();
	}

	private static String toolName(Tool tool) {
		return tool == null || tool.function() == null ? null : tool.function().name();
	}

	private static Predicate<String> matches(Pattern pattern) {
		return value -> value != null && pattern.matcher(value).matches();
	}

	private static int characterCount(InternalRequest request) {
		int count = 0;
		if (request.messages() != null) {
			for (Message message : request.messages()) {
				if (message != null && message.content() != null) {
					count += message.content().toString().length();
				}
			}
		}
		if (request.tools() != null) {
			for (Tool tool : request.tools()) {
				count += String.valueOf(tool).length();
			}
		}
		return count;
	}
}
