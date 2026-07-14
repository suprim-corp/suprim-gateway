package dev.suprim.gateway.instants;

import java.util.Map;

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

	public static final Map<String, String> MODEL_NAMES = Map.ofEntries(
			Map.entry("gpt-5.6-sol", "GPT 5.6 Sol"),
			Map.entry("gpt-5.6-terra", "GPT 5.6 Terra"),
			Map.entry("gpt-5.6-luna", "GPT 5.6 Luna"),
			Map.entry("gpt-5.5", "GPT 5.5"),
			Map.entry("gpt-5.5-pro", "GPT 5.5 Pro"),
			Map.entry("gpt-5.4", "GPT 5.4"),
			Map.entry("gpt-5.4-pro", "GPT 5.4 Pro"),
			Map.entry("gpt-5.4-mini", "GPT 5.4 Mini"),
			Map.entry("gpt-5.4-nano", "GPT 5.4 Nano"),
			Map.entry("gpt-5.3-codex-spark", "GPT 5.3 Codex Spark"),
			Map.entry("gpt-5.3-codex", "GPT 5.3 Codex"),
			Map.entry("gpt-5.2", "GPT 5.2"),
			Map.entry("gpt-5.2-pro", "GPT 5.2 Pro"),
			Map.entry("codex-auto-review", "Codex Auto Review")
	);

	private Codex() {}
}
