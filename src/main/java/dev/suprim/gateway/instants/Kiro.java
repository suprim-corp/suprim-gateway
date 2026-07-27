package dev.suprim.gateway.instants;

import java.util.Set;

public class Kiro {

	/**
	 * Regions that actually serve the Q / CodeWhisperer data plane. An account
	 * can be issued in a region that only hosts SSO OIDC (us-east-2, for
	 * example), in which case q.&lt;region&gt;.amazonaws.com does not resolve and
	 * requests must fall back to the default service region.
	 */
	private static final Set<String> SERVICE_REGIONS = Set.of(
			"us-east-1",
			"eu-central-1"
	);

	public static final String DEFAULT_SERVICE_REGION = "us-east-1";

	public static final String API_HOST_TEMPLATE = "https://runtime.%s.kiro.dev";
	public static final String Q_HOST_TEMPLATE = "https://q.%s.amazonaws.com";
	public static final String CODEWHISPERER_HOST = "https://codewhisperer.us-east-1.amazonaws.com";
	public static final String Q_HOST = "https://q.us-east-1.amazonaws.com";
	public static final String GENERATE_PATH = "/generateAssistantResponse";
	public static final String USAGE_LIMITS_PATH = "/getUsageLimits?origin=AI_EDITOR&resourceType=AGENTIC_REQUEST&isEmailRequired=true";
	public static final String AMZ_TARGET = "AmazonCodeWhispererStreamingService.GenerateAssistantResponse";
	public static final String AMZ_TARGET_Q = "AmazonQDeveloperStreamingService.SendMessage";
	public static final String CONTENT_TYPE = "application/x-amz-json-1.0";
	public static final String PROVIDER = "kiro";

	/**
	 * Maps an account region onto a region that serves the data plane, so a
	 * login-only region does not produce an unresolvable host.
	 */
	public static String serviceRegion(String region) {
		return region != null && SERVICE_REGIONS.contains(region)
				? region
				: DEFAULT_SERVICE_REGION;
	}

	/** Q host for the account's region, falling back to a region that exists. */
	public static String qHost(String region) {
		return String.format(Q_HOST_TEMPLATE, serviceRegion(region));
	}

	private Kiro() {}
}
