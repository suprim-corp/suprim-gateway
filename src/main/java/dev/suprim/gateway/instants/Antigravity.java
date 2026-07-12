package dev.suprim.gateway.instants;

public class Antigravity {

	public static final String CLOUDCODE_BASE = "https://cloudcode-pa.googleapis.com";
	public static final String CLOUDCODE_MODELS =
			CLOUDCODE_BASE + "/v1beta/models/";
	public static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
	public static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
	public static final String OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform openid email profile";
	public static final String USER_AGENT = "antigravity/ide/2.1.1 darwin/arm64";
	public static final String REDIRECT_URI = "http://localhost:51121/oauth-callback";
	public static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
	public static final String CLIENT_ID = rot("1071006060591-gzuffva2u21yper235igbybwu4t403rc.nccf.tbbtyrhfrepbagrag.pbz");
	public static final String CLIENT_SECRET = rot("TBPFCK-X58SJE486YqYW1zYO8fKP4m6dQNs");
	public static final String PROVIDER = "ANTIGRAVITY";

	private Antigravity() {}

	private static String rot(String input) {
		StringBuilder sb = new StringBuilder(input.length());
		for (char c : input.toCharArray()) {
			if (c >= 'a' && c <= 'z') {
				sb.append((char) ('a' + (c - 'a' + 13) % 26));
			} else if (c >= 'A' && c <= 'Z') {
				sb.append((char) ('A' + (c - 'A' + 13) % 26));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
