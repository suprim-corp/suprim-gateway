package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.kiro.KiroEventParser;
import dev.suprim.gateway.proxy.kiro.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.TokenEstimator;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;

@RequiredArgsConstructor
@Component
public class StreamHandler {

	private final TokenEstimator tokenEstimator;

	@Builder
	public record StreamResult(
			String content, int outputTokens, long firstTokenMs, double credits,
			boolean hasToolUse, Usage usage
	) {}

	public StreamResult streamToWriter(
			KiroResponse response,
			PrintWriter writer,
			StreamingEventWriter eventWriter,
			long startTime
	) throws Exception {
		KiroEventParser parser = new KiroEventParser();
		StreamingContentFilter filter = new StreamingContentFilter();
		StringBuilder fullText = new StringBuilder();
		int[] outputTokens = {0};
		long[] firstTokenMs = {-1};
		UsageAccumulator usage = new UsageAccumulator();
		boolean[] hasToolUse = {false};

		byte[] buf = new byte[8192];
		int read;
		try (InputStream body = response.body()) {
			while ((read = body.read(buf)) != -1) {
				byte[] chunk = new byte[read];
				System.arraycopy(buf, 0, chunk, 0, read);
				List<KiroEvent> events = parser.feed(chunk);
				for (KiroEvent event : events) {
					if (firstTokenMs[0] < 0 && isModelOutput(event)) {
						firstTokenMs[0] =
								System.currentTimeMillis() - startTime;
					}
					if ("metering".equals(event.type()) ||
					    "usage".equals(event.type())) {
						usage.accept(event);
						if ("usage".equals(event.type())) {
							eventWriter.write(event);
						}
						continue;
					}
					if ("tool_use".equals(event.type())) {
						hasToolUse[0] = true;
					}
					if ("reasoning".equals(event.type())) {
						eventWriter.write(event);
						writer.flush();
					} else if (
							"content".equals(event.type()) &&
							event.content() != null
					) {
						filter.accept(
								event.content(), filtered -> {
									if (filtered.isEmpty()) {
										return;
									}
									if (firstTokenMs[0] < 0) {
										firstTokenMs[0] =
												System.currentTimeMillis() -
												startTime;
									}
									fullText.append(filtered);
									outputTokens[0] += tokenEstimator.countTokens(
											filtered
									);
									try {
										eventWriter.write(
												KiroEvent.content(filtered));
										writer.flush();
									} catch (Exception e) {
										throw new RuntimeException(e);
									}
								}
						);
					} else {
						eventWriter.write(event);
						writer.flush();
					}
				}
			}
		}
		filter.flush(filtered -> {
					if (filtered.isEmpty()) {
						return;
					}
					if (firstTokenMs[0] < 0) {
						firstTokenMs[0] =
								System.currentTimeMillis() - startTime;
					}
					fullText.append(filtered);
					outputTokens[0] += tokenEstimator.countTokens(filtered);
					try {
						eventWriter.write(KiroEvent.content(filtered));
						writer.flush();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
		);
		Usage finalUsage = usage.result();
		return StreamResult.builder()
		                   .content(fullText.toString())
		                   .outputTokens(finalUsage.outputTokens() != null
				                   ? finalUsage.outputTokens()
				                   : outputTokens[0])
		                   .firstTokenMs(
				                   firstTokenMs[0] < 0 ? 0 : firstTokenMs[0]
		                   )
		                   .credits(finalUsage.credits())
		                   .hasToolUse(hasToolUse[0])
		                   .usage(finalUsage)
		                   .build();
	}

	@Builder
	public record CollectResult(
			String content, String reasoning, double credits, Usage usage
	) {}

	public CollectResult collectContent(KiroResponse response) throws Exception {
		List<KiroEvent> events = KiroEventParser.parseStream(response.body());
		StringBuilder content = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		UsageAccumulator usage = new UsageAccumulator();
		for (KiroEvent event : events) {
			if ("metering".equals(event.type()) ||
			    "usage".equals(event.type())) {
				usage.accept(event);
				continue;
			}
			if ("reasoning".equals(event.type()) && event.content() != null) {
				reasoning.append(event.content());
				continue;
			}
			if ("content".equals(event.type()) && event.content() != null) {
				content.append(event.content());
			}
		}
		Usage finalUsage = usage.result();
		return CollectResult.builder()
		                    .content(ThinkingExtractor.strip(content.toString()))
		                    .reasoning(reasoning.isEmpty() ? null : reasoning.toString())
		                    .credits(finalUsage.credits())
		                    .usage(finalUsage)
		                    .build();
	}

	public int countTokens(String text) {
		return tokenEstimator.countTokens(text);
	}

	@Builder
	public record Usage(
			Integer promptTokens,
			Integer outputTokens,
			Integer cacheReadTokens,
			Integer cacheCreationTokens,
			Double contextPercentage,
			double credits
	) {}

	static final class UsageAccumulator {
		private Integer promptTokens;
		private Integer outputTokens;
		private Integer cacheReadTokens;
		private Integer cacheCreationTokens;
		private Double contextPercentage;
		private double credits;

		void accept(KiroEvent event) {
			if ("metering".equals(event.type()) && event.credits() != null) {
				credits += event.credits();
				return;
			}
			KiroEvent.Usage snapshot = event.usage();
			if (snapshot == null) {
				return;
			}
			if (snapshot.promptTokens() != null) {
				promptTokens = snapshot.promptTokens();
			}
			if (snapshot.outputTokens() != null) {
				outputTokens = snapshot.outputTokens();
			}
			if (snapshot.cacheReadTokens() != null) {
				cacheReadTokens = snapshot.cacheReadTokens();
			}
			if (snapshot.cacheCreationTokens() != null) {
				cacheCreationTokens = snapshot.cacheCreationTokens();
			}
			if (snapshot.contextPercentage() != null) {
				contextPercentage = snapshot.contextPercentage();
			}
		}

		Usage result() {
			return Usage.builder()
			            .promptTokens(promptTokens)
			            .outputTokens(outputTokens)
			            .cacheReadTokens(cacheReadTokens)
			            .cacheCreationTokens(cacheCreationTokens)
			            .contextPercentage(contextPercentage)
			            .credits(credits)
			            .build();
		}
	}

	private boolean isModelOutput(KiroEvent event) {
		return "content".equals(event.type()) ||
		       "reasoning".equals(event.type()) ||
		       "tool_use".equals(event.type());
	}
}
