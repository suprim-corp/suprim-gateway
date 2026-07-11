package dev.suprim.gateway.proxy;

import dev.suprim.gateway.auth.KiroAuthManager;
import dev.suprim.gateway.config.AppConfig;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class KiroHttpClient {

	private static final Logger log = LoggerFactory.getLogger(KiroHttpClient.class);
	private static final long BASE_RETRY_DELAY = 1000;

	private final KiroAuthManager auth;
	private final KiroHeaders kiroHeaders;
	private final AppConfig config;
	private final ProxyChain proxyChain;

	@Builder
	public record KiroResponse(
			int status, InputStream body, String contentType
	) {}

	public KiroResponse request(
			String method,
			String url,
			String body,
			boolean stream
	) throws Exception {
		int maxRetries = stream ? config.firstTokenMaxRetries() : 3;

		proxyChain.resetAttempts();

		while (true) {
			HttpClient client = proxyChain.currentClient();
			if (client == null) {
				throw new IOException("[Proxy] All proxies exhausted");
			}

			try {
				return doRequest(client, method, url, body, stream, maxRetries);
			} catch (IOException e) {
				if (!proxyChain.hasProxies()) {
					throw e;
				}
				log.warn(
						"[Proxy] Error with current proxy: {}",
						e.getMessage()
				);
				proxyChain.onFailure();
			}
		}
	}

	private KiroResponse doRequest(
			HttpClient client,
			String method,
			String url,
			String body,
			boolean stream,
			int maxRetries
	) throws Exception {
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

				HttpResponse<InputStream> response = client.send(
						reqBuilder.build(),
						HttpResponse.BodyHandlers.ofInputStream()
				);
				int status = response.statusCode();

				if (status == 200) {
					String contentType = response.headers().firstValue(
							"content-type").orElse("");
					return KiroResponse.builder()
					                   .status(200)
					                   .body(response.body())
					                   .contentType(contentType)
					                   .build();
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
				log.error(
						"[Kiro] Upstream returned {}: content-type={}",
						status,
						contentType
				);
				return KiroResponse.builder()
				                   .status(status)
				                   .body(response.body())
				                   .contentType(contentType)
				                   .build();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			} catch (IOException e) {
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
			return KiroResponse.builder()
			                   .status(lastResponse.statusCode())
			                   .body(lastResponse.body())
			                   .contentType(contentType)
			                   .build();
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
