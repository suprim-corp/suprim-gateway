package dev.kiro.gateway.proxy;

import dev.kiro.gateway.auth.KiroAuthManager;
import dev.kiro.gateway.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class KiroHttpClient {

	private static final Logger log = LoggerFactory.getLogger(KiroHttpClient.class);
	private static final long BASE_RETRY_DELAY = 1000;

	private final KiroAuthManager auth;
	private final KiroHeaders kiroHeaders;
	private final AppConfig config;
	private final HttpClient httpClient;

	KiroHttpClient(
			KiroAuthManager auth,
			KiroHeaders kiroHeaders,
			AppConfig config
	) {
		this.auth = auth;
		this.kiroHeaders = kiroHeaders;
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
		                            .connectTimeout(Duration.ofSeconds(10))
		                            .build();
	}

	public record KiroResponse(int status, InputStream body, String contentType) {}

	public KiroResponse request(
			String method,
			String url,
			String body,
			boolean stream
	) throws Exception {
		int maxRetries = stream ? config.firstTokenMaxRetries() : 3;

		HttpResponse<InputStream> lastResponse = null;
		Exception lastError = null;

		for (int attempt = 0; attempt < maxRetries; attempt++) {
			try {
				String token = auth.getAccessToken();
				Map<String, String> headers = kiroHeaders.build(auth, token);

				HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(
						URI.create(url));
				headers.forEach(reqBuilder::header);

				if ("POST".equals(method) && body != null) {
					reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
				} else {
					reqBuilder.GET();
				}

				HttpResponse<InputStream> response = httpClient.send(
						reqBuilder.build(),
						HttpResponse.BodyHandlers.ofInputStream()
				);
				int status = response.statusCode();

				if (status == 200) {
					String contentType = response.headers().firstValue(
							"content-type").orElse("");
					return new KiroResponse(200, response.body(), contentType);
				}

				if (status == 403) {
					log.warn(
							"[Kiro] 403, refreshing token (attempt {}/{})",
							attempt + 1,
							maxRetries
					);
					auth.forceRefresh();
					continue;
				}

				if (status == 429) {
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn(
							"[Kiro] 429 rate limited, waiting {}ms (attempt {}/{})",
							delay,
							attempt + 1,
							maxRetries
					);
					Thread.sleep(delay);
					lastResponse = response;
					continue;
				}

				if (status >= 500) {
					long delay = BASE_RETRY_DELAY * (1L << attempt);
					log.warn(
							"[Kiro] {} from upstream, waiting {}ms (attempt {}/{})",
							status,
							delay,
							attempt + 1,
							maxRetries
					);
					Thread.sleep(delay);
					lastResponse = response;
					continue;
				}

				String contentType = response.headers().firstValue(
						"content-type").orElse("");
				return new KiroResponse(status, response.body(), contentType);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			} catch (Exception e) {
				lastError = e;
				long delay = BASE_RETRY_DELAY * (1L << attempt);
				log.error(
						"[Kiro] Network error: {}, waiting {}ms (attempt {}/{})",
						e.getMessage(),
						delay,
						attempt + 1,
						maxRetries
				);
				Thread.sleep(delay);
			}
		}

		if (lastResponse != null) {
			String contentType = lastResponse.headers().firstValue(
					"content-type").orElse("");
			return new KiroResponse(
					lastResponse.statusCode(),
					lastResponse.body(),
					contentType
			);
		}
		throw lastError != null ? lastError : new RuntimeException(
				"All retries exhausted");
	}

	public String getGenerateUrl() {
		return auth.getApiHost() + "/generateAssistantResponse";
	}

	public String getListModelsUrl() {
		String params = "origin=AI_EDITOR";
		if (auth.getProfileArn() != null)
			params += "&profileArn=" + auth.getProfileArn();
		return auth.getQHost() + "/ListAvailableModels?" + params;
	}
}
