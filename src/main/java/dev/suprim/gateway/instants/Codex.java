package dev.suprim.gateway.instants;

public final class Codex {

	public static final String PROVIDER = "CODEX";
	public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
	public static final String AUTH_URL = "https://auth.openai.com/oauth/authorize";
	public static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
	public static final String API_BASE = "https://chatgpt.com/backend-api/codex";
	public static final String SCOPE = "openid profile email offline_access";
	public static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
	public static final String CALLBACK_PATH = "/auth/callback";
	public static final int LOOPBACK_PORT = 1455;

	private Codex() {}
}
