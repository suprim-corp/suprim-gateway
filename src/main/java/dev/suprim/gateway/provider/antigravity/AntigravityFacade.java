package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.logging.LogTag;
import dev.suprim.gateway.logging.RequestLogEvent;
import dev.suprim.gateway.logging.RequestLogPublisher;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.Provider;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.kiro.KiroEvent;
import dev.suprim.gateway.proxy.StreamConverter;
import dev.suprim.gateway.utils.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
@Component
public class AntigravityFacade {

	private static final JsonMapper MAPPER = new JsonMapper();

	/**
	 * Signatures the upstream attached to tool calls, keyed by call id. The upstream only
	 * accepts a tool result back when the call is replayed with the signature it came with,
	 * and the client returns the results on a later request, so these outlive one request.
	 */
	private static final Map<String, String> THOUGHT_SIGNATURES = new ConcurrentHashMap<>();

	private final RequestLogPublisher logPublisher;
	private final StreamConverter streamConverter;
	private final CredentialStore credentialStore;
	private final AntigravityAccountAttempts accountAttempts;

	/**
	 * What every stage of one request needs to know about it: the wire format to answer in,
	 * and the details each outcome is logged with. Passed around instead of six parameters
	 * repeated on every handler.
	 */
	@Builder
	private record Call(
			String model,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			long startTime,
			HttpServletResponse httpRes
	) {

		int latencyMs() {
			return (int) (System.currentTimeMillis() - startTime);
		}

		/** A log event with everything this request already knows filled in. */
		RequestLogEvent.RequestLogEventBuilder log(String accountName, int status) {
			return RequestLogEvent.builder()
			                      .virtualKeyId(keyId)
			                      .accountId(accountName)
			                      .model(model)
			                      .requestedModel(model)
			                      .status(status)
			                      .latencyMs(latencyMs())
			                      .clientIp(clientIp);
		}
	}

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
		List<StoredAccount> accounts = credentialStore.findAllByProvider(
				Provider.ANTIGRAVITY.name()
		);
		if (accounts.isEmpty()) {
			ErrorResponse.openAi(
					httpRes,
					401,
					"Antigravity provider not connected. Visit /auth/antigravity to connect.",
					"provider_not_connected"
			);
			return;
		}

		Call call = Call.builder()
		                .model(model)
		                .inputTokens(inputTokens)
		                .keyId(keyId)
		                .clientIp(clientIp)
		                .format(format)
		                .startTime(System.currentTimeMillis())
		                .httpRes(httpRes)
		                .build();

		// One id for the whole client request, reused across account rotations and the
		// transport-level retries inside them, so the upstream sees the retries of a
		// single request rather than a burst of unrelated ones.
		String requestId = UUID.randomUUID().toString();

		AntigravityAccountAttempts.Outcome outcome = accountAttempts.run(
				accounts,
				model,
				projectId -> AntigravityPayloadBuilder.build(
						request, model, projectId, THOUGHT_SIGNATURES, requestId
				)
		);

		if (!outcome.succeeded()) {
			reportFailure(outcome.failure(), call);
			return;
		}

		if (stream) {
			handleStream(outcome.response(), outcome.accountName(), call);
		} else {
			handleNonStream(outcome.response(), outcome.accountName(), call);
		}
	}

	/**
	 * Reports the rotation's failure. A null {@code failure} means every account was
	 * rate-limited, which has no specific error to relay.
	 */
	private void reportFailure(
			AntigravityAccountAttempts.Failure failure,
			Call call
	) throws Exception {
		if (failure == null) {
			ErrorResponse.openAi(
					call.httpRes(),
					429,
					"All accounts rate-limited",
					"rate_limit_exhausted"
			);
			return;
		}
		handleError(failure, call);
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() > max ? value.substring(0, max) : value;
	}

	private void handleError(
			AntigravityAccountAttempts.Failure failure,
			Call call
	) throws Exception {
		log.error(
				LogTag.ANTIGRAVITY + "Upstream {} body: {}",
				failure.status(),
				failure.body() == null ? "" : failure.body()
		);

		logPublisher.publish(
				call.log(failure.accountName(), failure.status())
				    .promptTokens(call.inputTokens())
				    .streaming(false)
				    .errorMessage(truncate(failure.body(), 200))
				    .build()
		);

		ErrorResponse.openAi(
				call.httpRes(),
				failure.status(),
				"Antigravity upstream error",
				"upstream_error"
		);
	}

	private void handleStream(
			AntigravityHttpClient.AntigravityResponse response,
			String accountName,
			Call call
	) throws Exception {
		HttpServletResponse httpRes = call.httpRes();
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("text/event-stream; charset=utf-8");
		httpRes.setHeader("Cache-Control", "no-cache");

		AntigravityResponseWriter out = new AntigravityResponseWriter(
				streamConverter,
				httpRes.getWriter(),
				call.format(),
				generateId(call.format()),
				call.model()
		);
		out.preamble(call.inputTokens());

		StreamState state = new StreamState();
		AntigravitySseReader.Totals totals = AntigravitySseReader.read(
				response.body(),
				parsed -> relayChunk(parsed, out, state, call.startTime())
		);

		// The upstream's own counts when it reported them, the local tally otherwise: the
		// tally counts stream chunks rather than tokens, so it is only ever an estimate.
		int reportedInput = AntigravitySseReader.reportedOr(
				totals.usage(),
				AntigravityStreamConverter.Usage::promptTokens,
				call.inputTokens()
		);
		int reportedOutput = AntigravitySseReader.reportedOr(
				totals.usage(),
				AntigravityStreamConverter.Usage::completionTokens,
				state.outputTokens
		);

		out.finale(
				state.fullContent.toString(), state.hasToolUse, reportedInput, reportedOutput
		);

		log.debug(
				LogTag.ANTIGRAVITY +
				"Stream done: textChunks={}, toolCalls={}, hasToolUse={}, usage={}",
				state.outputTokens - state.toolIndex,
				state.toolIndex,
				state.hasToolUse,
				totals.usage()
		);

		logPublisher.publish(
				call.log(accountName, 200)
				    .promptTokens(reportedInput)
				    .completionTokens(reportedOutput > 0 ? reportedOutput : null)
				    .firstTokenMs(
						    state.firstTokenMs != null
								    ? state.firstTokenMs.intValue()
								    : null
				    )
				    .streaming(true)
				    .credits(billedCredits(totals))
				    .build()
		);
	}

	/** Credits the upstream billed, or null when it billed none or reported nothing. */
	private static Double billedCredits(AntigravitySseReader.Totals totals) {
		Double credits = totals.consumedCredits();
		return credits != null && credits > 0 ? credits : null;
	}

	/**
	 * What one stream accumulates as it is relayed. Mutable so the per-chunk callback can
	 * add to it; confined to a single {@link #handleStream} call, never shared.
	 */
	private static final class StreamState {

		private final StringBuilder fullContent = new StringBuilder();
		private int outputTokens;
		private int toolIndex;
		private boolean hasToolUse;
		private Long firstTokenMs;

		void markFirstToken(long startTime) {
			if (firstTokenMs == null) {
				firstTokenMs = System.currentTimeMillis() - startTime;
			}
		}
	}

	/** Relays one parsed chunk to the client and folds it into {@code state}. */
	private void relayChunk(
			AntigravityStreamConverter.ParsedChunk parsed,
			AntigravityResponseWriter out,
			StreamState state,
			long startTime
	) throws Exception {
		if (parsed.text() != null && !parsed.text().isEmpty()) {
			state.markFirstToken(startTime);
			state.fullContent.append(parsed.text());
			out.textDelta(parsed.text());
			state.outputTokens++;
		}

		if (parsed.functionCall() != null) {
			state.hasToolUse = true;
			state.markFirstToken(startTime);
			state.toolIndex++;

			String toolCallId = parsed.functionCall().id() != null
					? parsed.functionCall().id()
					: "call_" + UUID.randomUUID()
					                .toString()
					                .replace("-", "")
					                .substring(0, 20);

			// The upstream will only accept the tool result back if the call is replayed
			// with the signature it came with, so keep them keyed by call id.
			if (parsed.thoughtSignature() != null) {
				THOUGHT_SIGNATURES.put(toolCallId, parsed.thoughtSignature());
			}

			out.toolCall(
					KiroEvent.toolUse(
							parsed.functionCall().name(),
							parsed.functionCall().args(),
							toolCallId
					),
					state.toolIndex
			);
			state.outputTokens++;
		}
	}

	private void handleNonStream(
			AntigravityHttpClient.AntigravityResponse response,
			String accountName,
			Call call
	) throws Exception {
		StringBuilder content = new StringBuilder();
		AntigravitySseReader.Totals totals = AntigravitySseReader.read(
				response.body(),
				parsed -> {
					if (parsed.text() != null && !parsed.text().isEmpty()) {
						content.append(parsed.text());
					}
				}
		);

		String text = content.toString();
		String id = generateId(call.format());
		String model = call.model();
		// Four characters per token is a rough stand-in; the upstream's own count replaces
		// it whenever the response carries one.
		int reportedInput = AntigravitySseReader.reportedOr(
				totals.usage(),
				AntigravityStreamConverter.Usage::promptTokens,
				call.inputTokens()
		);
		int outputTokens = AntigravitySseReader.reportedOr(
				totals.usage(),
				AntigravityStreamConverter.Usage::completionTokens,
				text.length() / 4
		);

		HttpServletResponse httpRes = call.httpRes();
		httpRes.setCharacterEncoding("UTF-8");
		httpRes.setContentType("application/json; charset=utf-8");

		MAPPER.writeValue(
				httpRes.getWriter(),
				AntigravityResponseWriter.nonStreamingBody(
						streamConverter,
						call.format(),
						id,
						model,
						text,
						reportedInput,
						outputTokens
				)
		);

		logPublisher.publish(
				call.log(accountName, 200)
				    .promptTokens(reportedInput)
				    .completionTokens(outputTokens > 0 ? outputTokens : null)
				    .streaming(false)
				    .credits(billedCredits(totals))
				    .build()
		);
	}

	private String generateId(Format format) {
		return switch (format) {
			case ANTHROPIC -> "msg_" + UUID.randomUUID()
			                               .toString()
			                               .replace(
					                               "-",
					                               ""
			                               )
			                               .substring(0, 20);
			case RESPONSES -> "resp_" + UUID.randomUUID();
			default -> "chatcmpl-" + UUID.randomUUID();
		};
	}
}
