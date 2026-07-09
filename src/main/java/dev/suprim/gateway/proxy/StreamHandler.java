package dev.suprim.gateway.proxy;

import dev.suprim.gateway.proxy.KiroEventParser.KiroEvent;
import dev.suprim.gateway.proxy.KiroHttpClient.KiroResponse;
import dev.suprim.gateway.utils.TokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.List;

@RequiredArgsConstructor
@Component
public class StreamHandler {

	private final TokenEstimator tokenEstimator;

	public record StreamResult(String content, int outputTokens, long firstTokenMs) {}

	public StreamResult streamToWriter(
			KiroResponse response,
			PrintWriter writer,
			EventWriter eventWriter,
			long startTime
	) throws Exception {
		KiroEventParser parser = new KiroEventParser();
		StringBuilder fullText = new StringBuilder();
		int outputTokens = 0;
		long firstTokenMs = -1;

		byte[] buf = new byte[8192];
		int read;
		while ((read = response.body().read(buf)) != -1) {
			byte[] chunk = new byte[read];
			System.arraycopy(buf, 0, chunk, 0, read);
			List<KiroEvent> events = parser.feed(chunk);
			for (KiroEvent event : events) {
				String sse = eventWriter.convert(event);
				if (sse != null) {
					writer.write(sse);
					writer.flush();
				}
				if ("content".equals(event.type()) && event.content() != null) {
					if (firstTokenMs < 0) {
						firstTokenMs = System.currentTimeMillis() - startTime;
					}
					fullText.append(event.content());
					outputTokens += tokenEstimator.countTokens(event.content());
				}
			}
		}
		return new StreamResult(fullText.toString(), outputTokens, firstTokenMs < 0 ? 0 : firstTokenMs);
	}

	public String collectContent(KiroResponse response) throws Exception {
		List<KiroEvent> events = KiroEventParser.parseStream(response.body());
		StringBuilder content = new StringBuilder();
		for (KiroEvent event : events) {
			if ("content".equals(event.type()) && event.content() != null) {
				content.append(event.content());
			}
		}
		return content.toString();
	}

	public int countTokens(String text) {
		return tokenEstimator.countTokens(text);
	}

	@FunctionalInterface
	public interface EventWriter {
		String convert(KiroEvent event) throws Exception;
	}
}
