package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.proxy.ProxyChain;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.isNull;

@Slf4j
@Component
@RequiredArgsConstructor
public class KiroHttpClient {
	private static final long BASE_RETRY_DELAY = 1000;

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
			boolean stream,
			String accessToken
	) throws Exception {
		return request(method, url, body, stream, accessToken, null);
	}

	public KiroResponse request(
			String method,
			String url,
			String body,
			boolean stream,
			String accessToken,
			String amzTarget
	) throws Exception {
		int maxRetries = stream ? config.firstTokenMaxRetries() : 3;

		proxyChain.resetAttempts();

		while (true) {
			HttpClient client = proxyChain.currentClient();
			if (client == null) {
				throw new IOException("[Proxy] All proxies exhausted");
			}

			try {
				return doRequest(
						client,
						method,
						url,
						body,
						stream,
						maxRetries,
						accessToken,
						amzTarget
				);
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
			int maxRetries,
			String accessToken,
			String amzTarget
	) throws Exception {
		HttpResponse<InputStream> lastResponse = null;
		Exception lastError = null;

		for (int attempt = 0; attempt < maxRetries; attempt++) {
			try {
				Map<String, String> headers = kiroHeaders.build(accessToken);

				HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				                                            .uri(URI.create(url));
				if (amzTarget != null) {
					headers.put("x-amz-target", amzTarget);
				} else {
					headers.remove("x-amz-target");
				}
				headers.forEach(reqBuilder::header);
				log.info("[Kiro] Request headers: {}", headers.keySet());
				log.info("[Kiro] URL: {} | amzTarget: {}", url, amzTarget);

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
					String contentType = response.headers()
					                             .firstValue("content-type")
					                             .orElse("");
					return KiroResponse.builder()
					                   .status(403)
					                   .body(response.body())
					                   .contentType(contentType)
					                   .build();
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

		if (isNull(lastResponse)) {
			throw Optional.ofNullable(lastError)
			              .orElse(
					              new RuntimeException("All retries exhausted")
			              );
		}
		return KiroResponse.builder()
		                   .status(lastResponse.statusCode())
		                   .body(lastResponse.body())
		                   .contentType(
				                   lastResponse.headers()
				                               .firstValue("content-type")
				                               .orElse("")
		                   )
		                   .build();
	}
}
