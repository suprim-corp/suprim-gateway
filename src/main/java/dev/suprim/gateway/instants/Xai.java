package dev.suprim.gateway.instants;

import java.util.Map;

public final class Xai {

	public static final String PROVIDER = "XAI";
	public static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
	public static final String AUTH_URL = "https://auth.x.ai/oauth2/authorize";
	public static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
	public static final String API_BASE = "https://api.x.ai/v1";
	public static final String SCOPE = "openid profile email offline_access grok-cli:access api:access";
	public static final String CALLBACK_PATH = "/callback/xai";
	public static final String REDIRECT_URI = "http://127.0.0.1:56121/callback";

	public static final Map<String, String> MODEL_NAMES = Map.ofEntries(
			Map.entry("grok-4.5", "Grok 4.5"),
			Map.entry("grok-4.3", "Grok 4.3"),
			Map.entry("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning"),
			Map.entry("grok-4.20-0309-non-reasoning", "Grok 4.20"),
			Map.entry("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent"),
			Map.entry("grok-build-0.1", "Grok Build 0.1"),
			Map.entry("grok-imagine-image", "Grok Imagine Image"),
			Map.entry("grok-imagine-image-quality", "Grok Imagine Image Quality"),
			Map.entry("grok-imagine-video", "Grok Imagine Video"),
			Map.entry("grok-imagine-video-1.5", "Grok Imagine Video 1.5")
	);

	private Xai() {}
}
