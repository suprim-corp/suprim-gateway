package dev.suprim.gateway.provider.kiro;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs a short local command and returns its output, for the few host facts the JVM does not expose
 * the way Kiro IDE's Node runtime sees them.
 * <p>
 * Every failure — missing binary, non-zero exit, interruption — comes back as an empty string. Each
 * caller has a usable fallback, and a user agent built from a fallback is better than a request that
 * fails because a shell-out did not work.
 */
@Slf4j
final class KiroCommand {

	private KiroCommand() {}

	/**
	 * The command's trimmed stdout, or an empty string when it could not be run. All three process
	 * streams are closed even on failure, so a repeated call cannot leak file descriptors.
	 */
	static String output(String... command) {
		Process process = null;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true)
			                                     .start();
			String output;
			try (InputStream stdout = process.getInputStream();
			     OutputStream stdin = process.getOutputStream();
			     InputStream stderr = process.getErrorStream()) {
				output = new String(
						stdout.readAllBytes(),
						StandardCharsets.UTF_8
				).trim();
			}
			return process.waitFor() == 0 ? output : "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		} catch (Exception e) {
			log.debug(
					"[Kiro] Could not run {}: {}",
					command[0],
					e.getMessage()
			);
			return "";
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}
}
