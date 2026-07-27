package dev.suprim.gateway.provider.antigravity;

import lombok.Builder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads the {@code streamGenerateContent} SSE body and hands each parsed chunk to a
 * consumer, accumulating the figures that only the stream can tell us.
 * <p>
 * Usage counts and consumed credits arrive on some chunks only and are cumulative for the
 * whole request, so the last value seen is the total — the reader tracks that rather than
 * making every caller do it.
 */
final class AntigravitySseReader {

	private static final String DATA_PREFIX = "data: ";

	private AntigravitySseReader() {}

	/**
	 * What the stream reported by the time it ended. {@code usage} and
	 * {@code consumedCredits} are null when the upstream never reported them, so callers can
	 * tell that from a reported zero.
	 */
	@Builder
	record Totals(AntigravityStreamConverter.Usage usage, Double consumedCredits) {}

	/**
	 * Handles one parsed chunk. Distinct from {@link Consumer} because relaying a chunk
	 * writes to the client, which is allowed to fail.
	 */
	@FunctionalInterface
	interface ChunkHandler {

		void accept(AntigravityStreamConverter.ParsedChunk chunk) throws Exception;
	}

	/**
	 * Consumes {@code body} to completion, calling {@code onChunk} for every chunk that
	 * parses. Closes the body.
	 */
	static Totals read(InputStream body, ChunkHandler onChunk) throws Exception {
		AntigravityStreamConverter.Usage usage = null;
		Double consumedCredits = null;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith(DATA_PREFIX)) {
					continue;
				}
				String data = line.substring(DATA_PREFIX.length()).trim();
				if (data.isEmpty()) {
					continue;
				}

				AntigravityStreamConverter.ParsedChunk parsed =
						AntigravityStreamConverter.parseChunk(data);
				if (parsed == null) {
					continue;
				}

				if (parsed.usage() != null) {
					usage = parsed.usage();
				}
				if (parsed.consumedCredits() != null) {
					consumedCredits = parsed.consumedCredits();
				}
				onChunk.accept(parsed);
			}
		}

		return Totals.builder()
		             .usage(usage)
		             .consumedCredits(consumedCredits)
		             .build();
	}

	/**
	 * The count the upstream reported for one side of the request, or {@code fallback} when
	 * it reported none. A reported zero is taken at face value: the upstream billed nothing.
	 */
	static int reportedOr(
			AntigravityStreamConverter.Usage usage,
			Function<AntigravityStreamConverter.Usage, Integer> field,
			int fallback
	) {
		if (usage == null) {
			return fallback;
		}
		Integer reported = field.apply(usage);
		return reported != null ? reported : fallback;
	}
}
