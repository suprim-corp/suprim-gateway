package dev.suprim.gateway.provider.antigravity;

import dev.suprim.gateway.instants.Antigravity;
import dev.suprim.gateway.provider.antigravity.LoadCodeAssist.Request.ClientMetadata;
import dev.suprim.gateway.provider.antigravity.LoadCodeAssist.Request.IdeType;
import dev.suprim.gateway.provider.antigravity.LoadCodeAssist.Request.Mode;
import dev.suprim.gateway.provider.antigravity.LoadCodeAssist.Request.Platform;
import dev.suprim.gateway.provider.antigravity.LoadCodeAssist.Request.PluginType;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
class ProjectIdFetcher {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final HttpClient HTTP_CLIENT =
			HttpClient.newBuilder()
			          .connectTimeout(Duration.ofSeconds(10))
			          .build();

	/**
	 * Tried in order when discovering the project. {@code ANTIGRAVITY} is deliberately not
	 * in this list: it is the identity that unlocks subscription data, but its response
	 * carries no {@code cloudaicompanionProject}, so it would only add a wasted round trip
	 * here.
	 */
	private static final IdeType[] IDE_TYPES = {
			IdeType.VSCODE,
			IdeType.INTELLIJ,
			IdeType.CLOUD_SHELL,
			IdeType.IDE_UNSPECIFIED
	};

	static String fetch(String accessToken) throws IOException {
		for (IdeType ideType : IDE_TYPES) {
			try {
				String body = buildRequestBody(ideType, Platform.PLATFORM_UNSPECIFIED, null);
				HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				                                            .uri(URI.create(Antigravity.CLOUDCODE_BASE + "/v1internal:loadCodeAssist"));
				AntigravityHeaders.forControlPlane(accessToken).forEach(reqBuilder::header);
				reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));

				HttpResponse<String> response = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
				if (!isSuccess(response.statusCode())) {
					log.warn("[ProjectId] loadCodeAssist ({}) returned {}: {}", ideType, response.statusCode(), response.body());
					continue;
				}
				String responseBody = response.body();
				log.debug("[ProjectId] loadCodeAssist ({}) response: {}", ideType, responseBody);
				String projectId = parseProjectId(responseBody);
				if (projectId != null) {
					log.info("[ProjectId] Discovered projectId using {}: {}", ideType, projectId);
					return projectId;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("loadCodeAssist interrupted", e);
			} catch (Exception e) {
				log.warn("[ProjectId] Failed loadCodeAssist with {}: {}", ideType, e.getMessage());
			}
		}
		return null;
	}

	/**
	 * Body for subscription lookups. Reports {@code ideType: ANTIGRAVITY} because the
	 * backend gates subscription data on client identity: with {@code VSCODE} the same
	 * token is answered as a legacy Gemini Code Assist client — {@code paidTier} is
	 * omitted entirely and the response instead carries
	 * {@code ineligibleTiers[UNSUPPORTED_CLIENT]}. {@code mode} and {@code pluginType}
	 * make no difference; {@code ideType} alone decides.
	 */
	static String buildLoadCodeAssistBody() {
		return buildRequestBody(
				IdeType.ANTIGRAVITY, Platform.DARWIN_ARM64, Mode.FULL_ELIGIBILITY_CHECK
		);
	}

	/**
	 * Builds a {@code LoadCodeAssistRequest} body. A null {@code mode} is omitted, which
	 * the backend reads as {@code MODE_UNSPECIFIED}.
	 */
	private static String buildRequestBody(IdeType ideType, Platform platform, Mode mode) {
		return MAPPER.writeValueAsString(
				LoadCodeAssist.Request.builder()
				                      .metadata(
						                      ClientMetadata.builder()
						                                    .ideType(ideType)
						                                    .platform(platform)
						                                    .pluginType(PluginType.GEMINI)
						                                    .build()
				                      )
				                      .mode(mode)
				                      .build()
		);
	}

	/**
	 * Extracts the plan the account is actually on.
	 * <p>
	 * {@code paidTier} is the paid subscription (for example {@code g1-pro-tier} /
	 * "Google AI Pro"), {@code currentTier} the active free plan when there is no
	 * subscription. {@code allowedTiers} is deliberately not consulted: it lists the
	 * tiers the account is <em>permitted to select</em> and normally starts with
	 * {@code free-tier} even for subscribers.
	 */
	static String parseTier(String json) {
		try {
			LoadCodeAssist.Response response = MAPPER.readValue(
					json, LoadCodeAssist.Response.class
			);
			String paid = formatTier(response.paidTier());
			return paid != null ? paid : formatTier(response.currentTier());
		} catch (Exception ignored) {}
		return null;
	}

	private static String formatTier(LoadCodeAssist.Response.UserTier tier) {
		if (tier == null || tier.name() == null) {
			return null;
		}
		String description = tier.description();
		if (description == null || description.equals(tier.name())) {
			return tier.name();
		}
		return tier.name() + " — " + description;
	}

	static String parseProjectId(String json) {
		try {
			return projectId(
					MAPPER.readValue(json, LoadCodeAssist.Response.class)
					      .cloudaicompanionProject()
			);
		} catch (Exception e) {
			return null;
		}
	}

	private static String projectId(LoadCodeAssist.Response.Project project) {
		if (project == null || project.id() == null || project.id().isEmpty()) {
			return null;
		}
		return project.id();
	}

	static String parseOnboardResponse(String json) {
		try {
			OnboardUser.Operation operation = MAPPER.readValue(
					json, OnboardUser.Operation.class
			);
			if (!operation.done() || operation.response() == null) {
				return null;
			}
			return projectId(operation.response().cloudaicompanionProject());
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isSuccess(int status) {
		return status >= 200 && status < 300;
	}
}
