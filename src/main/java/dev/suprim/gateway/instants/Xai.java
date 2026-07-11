package dev.suprim.gateway.instants;

public final class Xai {

	public static final String PROVIDER = "XAI";
	public static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
	public static final String AUTH_URL = "https://auth.x.ai/oauth2/authorize";
	public static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
	public static final String API_BASE = "https://api.x.ai/v1";
	public static final String SCOPE = "openid profile email offline_access grok-cli:access api:access";
	public static final String CALLBACK_PATH = "/callback/xai";
	public static final String REDIRECT_URI = "http://127.0.0.1:56121/callback";

	private Xai() {}
}
