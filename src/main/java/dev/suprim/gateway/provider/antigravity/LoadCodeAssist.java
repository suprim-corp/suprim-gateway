package dev.suprim.gateway.provider.antigravity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request and response shapes for {@code /v1internal:loadCodeAssist}, mirroring the
 * {@code google.internal.cloud.code.v1internal} protos. Only the fields the gateway reads
 * are modelled; the rest of the response is ignored.
 */
final class LoadCodeAssist {

	private LoadCodeAssist() {}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Request(
			ClientMetadata metadata,
			Mode mode
	) {

		@Builder
		@JsonInclude(JsonInclude.Include.NON_NULL)
		record ClientMetadata(
				IdeType ideType,
				Platform platform,
				PluginType pluginType
		) {}

		/**
		 * {@code LoadCodeAssistRequest.Mode}. Omitted entirely when null.
		 */
		enum Mode {
			MODE_UNSPECIFIED,
			FULL_ELIGIBILITY_CHECK,
			HEALTH_CHECK
		}

		/**
		 * {@code ClientMetadata.IdeType}, trimmed to the values the gateway sends. The
		 * backend gates subscription data on this field: {@code ANTIGRAVITY} is treated as
		 * a current client, while {@code VSCODE} and the other IDE identities are answered
		 * as legacy Gemini Code Assist clients.
		 */
		enum IdeType {
			IDE_UNSPECIFIED,
			VSCODE,
			JETBRAINS,
			CLOUD_SHELL,
			ANTIGRAVITY
		}

		/**
		 * {@code ClientMetadata.Platform}.
		 */
		enum Platform {
			PLATFORM_UNSPECIFIED,
			DARWIN_ARM64,
			DARWIN_AMD64,
			LINUX_ARM64,
			LINUX_AMD64,
			WINDOWS_AMD64
		}

		/**
		 * {@code ClientMetadata.PluginType}.
		 */
		enum PluginType {
			PLUGIN_UNSPECIFIED,
			CLOUD_CODE,
			GEMINI
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(
			UserTier paidTier,
			UserTier currentTier,
			List<UserTier> allowedTiers,
			Project cloudaicompanionProject
	) {

		/**
		 * {@code UserTier}: one selectable or active plan.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		record UserTier(
				String id,
				String name,
				String description
		) {}

		/**
		 * {@code cloudaicompanionProject} arrives either as a bare project id string or as
		 * an object carrying one, so both shapes are accepted.
		 */
		record Project(String id) {

			@JsonCreator
			static Project from(JsonNode node) {
				if (node == null || node.isNull()) {
					return null;
				}
				if (node.isObject()) {
					JsonNode id = node.get("id");
					return new Project(
							id != null && !id.isNull() ? id.asString() : null);
				}
				return new Project(node.asString());
			}
		}
	}
}
