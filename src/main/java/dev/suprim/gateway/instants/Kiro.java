package dev.suprim.gateway.instants;

public class Kiro {

	public static final String API_HOST_TEMPLATE = "https://runtime.%s.kiro.dev";
	public static final String Q_HOST_TEMPLATE = "https://q.%s.amazonaws.com";
	public static final String USAGE_LIMITS_PATH = "/getUsageLimits?origin=AI_EDITOR&resourceType=AGENTIC_REQUEST&isEmailRequired=true";
	public static final String USER_AGENT_PREFIX = "aws-sdk-js/1.0.27 os/darwin arch/arm64 lang/js md/nodejs#22.0.0 KiroIDE-0.7.45-";
	public static final String AMZ_TARGET = "AmazonCodeWhispererStreamingService.GenerateAssistantResponse";
	public static final String CONTENT_TYPE = "application/x-amz-json-1.0";
	public static final String PROVIDER = "kiro";

	private Kiro() {}
}
