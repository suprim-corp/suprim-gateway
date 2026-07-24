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

	public record StreamResult(
			String content, int outputTokens, long firstTokenMs, double credits,
			boolean hasToolUse
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
		double[] credits = {0};
		boolean[] hasToolUse = {false};

		byte[] buf = new byte[8192];
		int read;
		try (InputStream body = response.body()) {
			while ((read = body.read(buf)) != -1) {
				byte[] chunk = new byte[read];
				System.arraycopy(buf, 0, chunk, 0, read);
				List<KiroEvent> events = parser.feed(chunk);
				for (KiroEvent event : events) {
					if ("metering".equals(event.type())) {
						credits[0] += event.credits();
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
									if (filtered.isEmpty()) return;
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
					if (filtered.isEmpty()) return;
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
		return new StreamResult(
				fullText.toString(),
				outputTokens[0],
				firstTokenMs[0] < 0 ? 0 : firstTokenMs[0],
				credits[0],
				hasToolUse[0]
		);
	}

	@Builder
	public record CollectResult(
			String content, String reasoning, double credits
	) {}

	public CollectResult collectContent(KiroResponse response) throws Exception {
		List<KiroEvent> events = KiroEventParser.parseStream(response.body());
		StringBuilder content = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		double credits = 0;
		for (KiroEvent event : events) {
			if ("metering".equals(event.type())) {
				credits += event.credits();
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
		return CollectResult.builder()
		                    .content(ThinkingExtractor.strip(content.toString()))
		                    .reasoning(reasoning.isEmpty() ? null : reasoning.toString())
		                    .credits(credits)
		                    .build();
	}

	public int countTokens(String text) {
		return tokenEstimator.countTokens(text);
	}
}
