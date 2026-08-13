package dev.suprim.gateway.provider.antigravity;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityHttpClientTest {

	private static final long NO_BACKOFF = 0;

	@Test
	void buildUrl_correctFormat() {
		String url = AntigravityHttpClient.buildUrl();

		// The daily host, not prod: prod answers every stream request with a 429 regardless of
		// how much quota the account has left.
		assertEquals(
				"https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
				url
		);
	}

	@Test
	void buildHeaders_containsRequiredHeaders() {
		Map<String, String> headers = AntigravityHttpClient.buildHeaders("ya29.test-token");

		assertEquals("Bearer ya29.test-token", headers.get("Authorization"));
		assertEquals("application/json", headers.get("Content-Type"));
		assertEquals("antigravity/ide/2.1.1 darwin/arm64", headers.get("User-Agent"));
	}

	@Test
	void buildProjectBody_includesProjectWhenPresent() {
		assertEquals("{\"project\":\"projects/test\"}", AntigravityHttpClient.buildProjectBody("projects/test"));
		assertEquals("{}", AntigravityHttpClient.buildProjectBody(null));
	}

	@Test
	void parseQuotaSummary_returnsNormalizedValues() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"quotaInfo":{"remainingFraction":0.42,"resetTime":"2026-07-25T00:00:00Z"}}
				""");

		assertEquals(42, quota.quota());
		assertEquals("2026-07-25T00:00:00Z", quota.resetTime());
	}

	@Test
	void parseQuotaSummary_returnsEveryBucketAcrossGroups() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary(GROUPED_QUOTA);

		List<AntigravityQuota.Bucket> buckets = quota.buckets();
		assertEquals(4, buckets.size());

		assertEquals("Gemini Models", buckets.getFirst().group());
		assertEquals("Weekly Limit", buckets.getFirst().label());
		assertEquals(100, buckets.getFirst().quota());
		assertEquals("2026-08-03T15:16:28Z", buckets.getFirst().resetTime());

		assertEquals("Claude and GPT models", buckets.get(2).group());
		assertEquals("Five Hour Limit", buckets.get(3).label());
	}

	@Test
	void parseQuotaSummary_headlineMirrorsMostConstrainedBucket() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary(GROUPED_QUOTA);

		assertEquals(30, quota.quota());
		assertEquals("2026-07-27T20:52:05Z", quota.resetTime());
	}

	@Test
	void parseQuotaSummary_skipsBucketsWithoutUsableFraction() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"groups":[{"displayName":"Gemini Models","buckets":[
				{"bucketId":"a","displayName":"Weekly Limit","remainingFraction":0.5},
				{"bucketId":"b","displayName":"No Fraction"},
				{"bucketId":"c","displayName":"Out Of Range","remainingFraction":1.4}]}]}
				""");

		assertEquals(1, quota.buckets().size());
		assertEquals("Weekly Limit", quota.buckets().getFirst().label());
		assertEquals(50, quota.quota());
	}

	@Test
	void parseQuotaSummary_keepsBucketsReportingAnAbsoluteCount() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"groups":[{"displayName":"Gemini Models","buckets":[
				{"bucketId":"a","displayName":"Weekly Limit","remainingFraction":0.5},
				{"bucketId":"b","displayName":"Credits","remainingAmount":"120",
				 "resetTime":"2026-08-03T15:16:28Z"}]}]}
				""");

		assertEquals(2, quota.buckets().size());

		AntigravityQuota.Bucket counted = quota.buckets().get(1);
		assertEquals("Credits", counted.label());
		assertEquals(120L, counted.remaining());
		assertNull(counted.quota());
		assertEquals("2026-08-03T15:16:28Z", counted.resetTime());
	}

	@Test
	void parseQuotaSummary_headlineIgnoresCountBasedBuckets() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"groups":[{"displayName":"Gemini Models","buckets":[
				{"bucketId":"a","displayName":"Weekly Limit","remainingFraction":0.5,
				 "resetTime":"2026-08-03T15:16:28Z"},
				{"bucketId":"b","displayName":"Credits","remainingAmount":"3"}]}]}
				""");

		assertEquals(50, quota.quota());
		assertEquals("2026-08-03T15:16:28Z", quota.resetTime());
	}

	@Test
	void parseQuotaSummary_omitsHeadlineWhenEveryBucketIsCountBased() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"groups":[{"displayName":"Gemini Models","buckets":[
				{"bucketId":"b","displayName":"Credits","remainingAmount":"7"}]}]}
				""");

		assertNull(quota.quota());
		assertNull(quota.resetTime());
		assertEquals(1, quota.buckets().size());
		assertEquals(7L, quota.buckets().getFirst().remaining());
	}

	@Test
	void parseQuotaSummary_skipsNegativeCounts() {
		AntigravityQuota quota = AntigravityHttpClient.parseQuotaSummary("""
				{"groups":[{"displayName":"Gemini Models","buckets":[
				{"bucketId":"a","displayName":"Weekly Limit","remainingFraction":0.5},
				{"bucketId":"b","displayName":"Broken","remainingAmount":"-1"}]}]}
				""");

		assertEquals(1, quota.buckets().size());
		assertEquals("Weekly Limit", quota.buckets().getFirst().label());
	}

	/**
	 * Shape captured from a live {@code fetchAvailableModels} response, trimmed to the
	 * capability fields. PDF and audio support show up only in {@code supportedMimeTypes}.
	 */
	@Test
	void parseModelsWithQuota_carriesCapabilityFields() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/gemini-3.1-pro-low":{
				"displayName":"Gemini 3.1 Pro (Low)","supportsImages":true,
				"supportsVideo":true,"supportsThinking":true,"thinkingBudget":1001,
				"minThinkingBudget":128,"maxTokens":1048576,"maxOutputTokens":65535,
				"supportedMimeTypes":{"image/png":true,"application/pdf":true,
				"audio/webm;codecs=opus":true,"video/mp4":true}}}}
				""");

		Map<String, Object> model = models.getFirst();
		assertEquals("gemini-3.1-pro-low", model.get("id"));
		assertEquals(true, model.get("supportsImages"));
		assertEquals(true, model.get("supportsVideo"));
		assertEquals(true, model.get("supportsThinking"));
		assertEquals(1001, model.get("thinkingBudget"));
		assertEquals(128, model.get("minThinkingBudget"));
		assertEquals(1048576, model.get("maxInputTokens"));
		assertEquals(65535, model.get("maxOutputTokens"));
		assertEquals(true, model.get("supportsPdf"));
		assertEquals(true, model.get("supportsAudio"));
	}

	/**
	 * A model whose MIME list has no PDF or audio entry is reported as not supporting them,
	 * since the list is exhaustive when present.
	 */
	@Test
	void parseModelsWithQuota_mimeListWithoutPdfOrAudio_reportsUnsupported() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/claude-sonnet-4-6":{"supportsImages":true,
				"supportedMimeTypes":{"image/png":true,"video/mp4":true}}}}
				""");

		Map<String, Object> model = models.getFirst();
		assertEquals(false, model.get("supportsPdf"));
		assertEquals(false, model.get("supportsAudio"));
		assertEquals(true, model.get("supportsVideo"));
	}

	/**
	 * Live responses carry {@code video/*} MIME types for models that omit the
	 * {@code supportsVideo} boolean, so the MIME list has to win over the boolean's absence.
	 */
	@Test
	void parseModelsWithQuota_videoMimesWithoutBoolean_reportsVideoSupported() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/gemini-2.5-pro":{"supportsImages":true,
				"supportedMimeTypes":{"video/mp4":true,"video/audio/wav":true}}}}
				""");

		assertEquals(true, models.getFirst().get("supportsVideo"));
	}

	/** No MIME list at all leaves the boolean as the only signal. */
	@Test
	void parseModelsWithQuota_withoutMimeList_fallsBackToVideoBoolean() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/some-model":{"supportsVideo":true}}}
				""");

		Map<String, Object> model = models.getFirst();
		assertEquals(true, model.get("supportsVideo"));
		assertFalse(model.containsKey("supportsPdf"));
	}

	/** A MIME list naming no video type means no video, even without the boolean. */
	@Test
	void parseModelsWithQuota_mimeListWithoutVideo_reportsVideoUnsupported() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/text-only":{"supportedMimeTypes":{"text/plain":true}}}}
				""");

		assertEquals(false, models.getFirst().get("supportsVideo"));
	}

	/** A model the upstream says nothing about must not gain invented capability keys. */
	@Test
	void parseModelsWithQuota_modelWithoutCapabilities_omitsThoseKeys() {
		List<Map<String, Object>> models = AntigravityHttpClient.parseModelsWithQuota("""
				{"models":{"models/chat_20706":{"maxTokens":16384}}}
				""");

		Map<String, Object> model = models.getFirst();
		assertEquals(16384, model.get("maxInputTokens"));
		assertFalse(model.containsKey("supportsImages"));
		assertFalse(model.containsKey("supportsPdf"));
		assertFalse(model.containsKey("supportsThinking"));
		assertFalse(model.containsKey("maxOutputTokens"));
	}

	/** Shape captured from a live {@code retrieveUserQuotaSummary} response. */
	private static final String GROUPED_QUOTA = """
			{"groups":[
			 {"displayName":"Gemini Models",
			  "description":"Models within this group: Gemini Flash, Gemini Pro",
			  "buckets":[
			   {"bucketId":"gemini-weekly","displayName":"Weekly Limit","window":"weekly",
			    "resetTime":"2026-08-03T15:16:28Z","remainingFraction":0.9999863,
			    "description":"You have used some of your weekly limit."},
			   {"bucketId":"gemini-5h","displayName":"Five Hour Limit","window":"5h",
			    "resetTime":"2026-07-27T20:16:28Z","remainingFraction":0.75}]},
			 {"displayName":"Claude and GPT models",
			  "buckets":[
			   {"bucketId":"3p-weekly","displayName":"Weekly Limit","window":"weekly",
			    "resetTime":"2026-08-03T15:52:05Z","remainingFraction":0.6},
			   {"bucketId":"3p-5h","displayName":"Five Hour Limit","window":"5h",
			    "resetTime":"2026-07-27T20:52:05Z","remainingFraction":0.3}]}]}
			""";

	@Test
	void parseQuotaSummary_rejectsInvalidOrMissingFractions() {
		assertEquals(AntigravityQuota.none(), AntigravityHttpClient.parseQuotaSummary("{}"));
		assertEquals(
				AntigravityQuota.none(),
				AntigravityHttpClient.parseQuotaSummary("{\"remainingFraction\":1.2}"),
				"A fraction outside 0..1 is unusable, not a quota of its own"
		);
		assertEquals(
				AntigravityQuota.none(),
				AntigravityHttpClient.parseQuotaSummary("not json")
		);
	}

	@Test
	void call_returnsFirstSuccessWithoutRetrying() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			server.start();

			AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.postWithRetry(
					server.url("/v1internal:streamGenerateContent").toString(),
					"{\"request\":{}}",
					"ya29.token",
					NO_BACKOFF
			);

			assertEquals(200, response.status());
			assertEquals("ok", read(response));
			assertEquals(1, server.getRequestCount());

			RecordedRequest request = server.takeRequest();
			assertEquals("Bearer ya29.token", request.getHeader("Authorization"));
			assertEquals("{\"request\":{}}", request.getBody().readUtf8());
		}
	}

	@Test
	void call_passesNonRetryableStatusStraightBack() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setResponseCode(403).setBody("denied"));
			server.start();

			AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.postWithRetry(
					server.url("/").toString(), "{}", "token", NO_BACKOFF
			);

			assertEquals(403, response.status());
			assertEquals("denied", read(response));
			assertEquals(1, server.getRequestCount());
		}
	}

	@Test
	void call_retriesRetryableStatusThenSucceeds() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
			server.enqueue(new MockResponse().setResponseCode(200).setBody("recovered"));
			server.start();

			AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.postWithRetry(
					server.url("/").toString(), "{}", "token", NO_BACKOFF
			);

			assertEquals(200, response.status());
			assertEquals("recovered", read(response));
			assertEquals(2, server.getRequestCount());
		}
	}

	@Test
	void call_returnsLastStatusWhenEveryAttemptIsRetryable() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			for (int i = 0; i < 3; i++) {
				server.enqueue(new MockResponse().setResponseCode(429).setBody("attempt " + i));
			}
			server.start();

			AntigravityHttpClient.AntigravityResponse response = AntigravityHttpClient.postWithRetry(
					server.url("/").toString(), "{}", "token", NO_BACKOFF
			);

			assertEquals(429, response.status());
			assertEquals("attempt 2", read(response));
			assertEquals(3, server.getRequestCount());
		}
	}

	@Test
	void call_propagatesNetworkErrorAfterExhaustingRetries() throws Exception {
		MockWebServer server = new MockWebServer();
		server.start();
		String url = server.url("/").toString();
		server.close();

		assertThrows(
				IOException.class,
				() -> AntigravityHttpClient.postWithRetry(url, "{}", "token", NO_BACKOFF)
		);
	}

	private static String read(AntigravityHttpClient.AntigravityResponse response) throws IOException {
		try (InputStream body = response.body()) {
			return new String(body.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
