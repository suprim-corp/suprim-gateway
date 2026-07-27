package dev.suprim.gateway.provider.kiro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The user-agent strings Kiro IDE's AWS SDK sends, reproduced pair by pair.
 * <p>
 * The IDE does not carry a literal user-agent string: the SDK assembles one from name/version pairs
 * and escapes each through a fixed rule, and the IDE contributes its own
 * {@code KiroIDE <version> <machineId>} pair on top. Both header values are built here from the
 * same pairs so they stay consistent with each other, as the SDK's do.
 * <p>
 * Values captured from Kiro IDE 1.0.228 (Electron 39.6.0, Node 22.22.0). Version constants below
 * are what the IDE bundles; they go stale when it updates, which shows up as a user agent naming an
 * older client rather than as a failure.
 */
public final class KiroUserAgent {

	private static final String SDK_NAME = "aws-sdk-js";
	private static final String IDE_NAME = "KiroIDE";
	private static final String IDE_VERSION = "1.0.228";
	private static final String NODE_VERSION = "22.22.0";
	private static final String UA_METADATA_VERSION = "2.1";

	/**
	 * Bundled {@code @aws/codewhisperer-streaming-client}, which serves the data plane.
	 */
	private static final String STREAMING_CLIENT_VERSION = "1.0.44";
	private static final String STREAMING_SERVICE_ID = "CodeWhispererStreaming";

	/**
	 * Bundled {@code @amzn/codewhisperer-runtime}, which serves the control plane.
	 */
	private static final String RUNTIME_CLIENT_VERSION = "1.0.0";
	private static final String RUNTIME_SERVICE_ID = "CodeWhispererRuntime";

	/**
	 * Characters the SDK replaces in a pair's name, per its own escaping rule.
	 */
	private static final Pattern NAME_ESCAPE =
			Pattern.compile("[^!$%&'*+\\-.^_`|~\\w]");

	/**
	 * As {@link #NAME_ESCAPE}, but {@code #} is legal in a version.
	 */
	private static final Pattern VALUE_ESCAPE =
			Pattern.compile("[^!$%&'*+\\-.^_`|~\\w#]");

	private static final String ESCAPE_CHAR = "-";

	private static volatile String cachedKernelRelease;

	private KiroUserAgent() {}

	/**
	 * The full {@code User-Agent} for the streaming data plane.
	 */
	public static String streaming() {
		return fullUserAgent(STREAMING_SERVICE_ID, STREAMING_CLIENT_VERSION);
	}

	/**
	 * The full {@code User-Agent} for the control-plane RPCs.
	 */
	public static String controlPlane() {
		return fullUserAgent(RUNTIME_SERVICE_ID, RUNTIME_CLIENT_VERSION);
	}

	/**
	 * The {@code x-amz-user-agent} value, which the SDK narrows to the {@code aws-sdk-*} pairs plus
	 * the IDE's own pair — not the whole string.
	 */
	private static String amzUserAgent(String clientVersion) {
		return String.join(" ", escape(SDK_NAME, clientVersion), idePair());
	}

	/**
	 * {@code x-amz-user-agent} for the streaming data plane.
	 */
	public static String amzStreaming() {
		return amzUserAgent(STREAMING_CLIENT_VERSION);
	}

	/**
	 * {@code x-amz-user-agent} for the control-plane RPCs.
	 */
	public static String amzControlPlane() {
		return amzUserAgent(RUNTIME_CLIENT_VERSION);
	}

	/**
	 * The SDK's pair order: client, ua metadata, os, language, runtime, then the API pair and the
	 * caller's custom pair last.
	 */
	private static String fullUserAgent(
			String serviceId,
			String clientVersion
	) {
		List<String> pairs = new ArrayList<>();
		pairs.add(escape(SDK_NAME, clientVersion));
		pairs.add(escape("ua", UA_METADATA_VERSION));
		pairs.add(escape("os/" + osFamily(), osVersion()));
		pairs.add(escape("lang/js", null));
		pairs.add(escape("md/nodejs", NODE_VERSION));
		pairs.add(escape("api/" + serviceId, clientVersion));
		pairs.add(idePair());
		return String.join(" ", pairs);
	}

	/**
	 * The IDE's own pair. It is one space-separated name with no version, so escaping collapses the
	 * spaces to dashes — which is why the value reads {@code KiroIDE-1.0.228-<hash>}.
	 */
	private static String idePair() {
		return escape(
				IDE_NAME + " " + IDE_VERSION + " " + KiroMachineId.get(),
				null
		);
	}

	/**
	 * The SDK reports Node's {@code os.platform()} value, not Java's {@code os.name}.
	 */
	private static String osFamily() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) {
			return "darwin";
		}
		return os.contains("win") ? "win32" : "linux";
	}

	/**
	 * Node's {@code os.release()} value — the kernel release, not the product version.
	 * <p>
	 * The two differ on macOS: the JVM's {@code os.version} reports the product version (26.5.1)
	 * where {@code uname -r} reports the Darwin kernel (25.5.0), and the IDE sends the kernel. On
	 * Linux and Windows the JVM property already matches Node, so only macOS shells out — and if
	 * that fails, the JVM value is a closer answer than none.
	 */
	private static String osVersion() {
		String fromJvm = System.getProperty("os.version", "");
		if (!"darwin".equals(osFamily())) {
			return fromJvm;
		}
		String kernel = kernelRelease();
		return kernel.isEmpty() ? fromJvm : kernel;
	}

	/**
	 * Cached: the kernel release cannot change while the process runs. An empty string means the
	 * lookup already ran and failed, so it is not retried on every request.
	 */
	private static String kernelRelease() {
		String resolved = cachedKernelRelease;
		if (resolved == null) {
			resolved = KiroCommand.output("uname", "-r");
			cachedKernelRelease = resolved;
		}
		return resolved;
	}

	/**
	 * One pair escaped as the SDK escapes it: illegal characters become dashes, an {@code api}
	 * prefix lowercases its name, and the parts join as {@code prefix/name#version}, skipping any
	 * that are empty.
	 */
	private static String escape(String name, String version) {
		StringBuilder escapedName = new StringBuilder();
		String[] segments = name.split("/", -1);
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				escapedName.append('/');
			}
			escapedName.append(
					NAME_ESCAPE.matcher(segments[i])
					           .replaceAll(ESCAPE_CHAR)
			);
		}
		String fullName = escapedName.toString();
		String escapedVersion = version == null
				? null
				: VALUE_ESCAPE.matcher(version).replaceAll(ESCAPE_CHAR);

		int separator = fullName.indexOf('/');
		String prefix = separator < 0 ? "" : fullName.substring(0, separator);
		String shortName = separator < 0
				? fullName
				: fullName.substring(separator + 1);
		if ("api".equals(prefix)) {
			shortName = shortName.toLowerCase(Locale.ROOT);
		}

		List<String> parts = Stream.of(prefix, shortName, escapedVersion)
		                           .filter(part -> part != null &&
		                                           !part.isEmpty()
		                           )
		                           .toList();
		StringBuilder pair = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			pair.append(switch (i) {
				case 0 -> "";
				case 1 -> "/";
				default -> "#";
			}).append(parts.get(i));
		}
		return pair.toString();
	}
}
