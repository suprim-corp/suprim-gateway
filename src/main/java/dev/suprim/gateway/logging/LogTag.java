package dev.suprim.gateway.logging;

public final class LogTag {

	private static final String PURPLE = "\033[35m";
	private static final String GRAY = "\033[90m";
	private static final String GREEN = "\033[32m";
	private static final String RESET = "\033[0m";

	public static final String KIRO = PURPLE + "[Kiro]" + RESET + " ";
	public static final String XAI = GRAY + "[xAI]" + RESET + " ";
	public static final String ANTIGRAVITY = GREEN + "[Antigravity]" + RESET + " ";
	public static final String CODEX = "\033[34m" + "[Codex]" + RESET + " ";
	public static final String DEEPSEEK = "\033[36m" + "[DeepSeek]" + RESET + " ";

	private LogTag() {}
}
