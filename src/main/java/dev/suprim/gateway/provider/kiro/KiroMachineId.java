package dev.suprim.gateway.provider.kiro;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The machine identifier Kiro IDE puts in its user agent, derived the same way the IDE's
 * {@code node-machine-id} dependency derives it.
 * <p>
 * The IDE reads the platform's own machine GUID and reports its SHA-256 — on macOS the
 * {@code IOPlatformUUID} from {@code ioreg}, on Linux {@code /etc/machine-id}, on Windows the
 * registry's {@code MachineGuid}. Sending a value derived differently would make requests
 * distinguishable from the IDE's, which is the one thing this is for.
 * <p>
 * Resolved once and cached: it identifies the host, so it must not vary between requests. When the
 * platform lookup fails the value falls back to {@code UNDETERMINED_MACHINE_ID}, matching what the
 * IDE sends when its own lookup fails.
 */
@Slf4j
final class KiroMachineId {

	/**
	 * What the IDE reports when it cannot read a machine id.
	 */
	private static final String UNDETERMINED = "UNDETERMINED_MACHINE_ID";

	/**
	 * {@code node-machine-id} strips these before hashing, so the digest must match.
	 */
	private static final Pattern STRIPPED = Pattern.compile(
			"[=\\s\"]",
			Pattern.CASE_INSENSITIVE
	);

	private static final String IOREG_KEY = "IOPlatformUUID";

	private static volatile String cached;

	private KiroMachineId() {}

	/**
	 * The hashed machine id, resolved on first call and reused afterwards.
	 */
	static String get() {
		String value = cached;
		if (value == null) {
			synchronized (KiroMachineId.class) {
				value = cached;
				if (value == null) {
					value = resolve();
					cached = value;
				}
			}
		}
		return value;
	}

	private static String resolve() {
		try {
			String raw = platformMachineId();
			return raw == null || raw.isBlank() ? UNDETERMINED : sha256(raw);
		} catch (Exception e) {
			log.debug("[Kiro] Could not read machine id: {}", e.getMessage());
			return UNDETERMINED;
		}
	}

	private static String platformMachineId() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) {
			return darwinPlatformUuid();
		}
		if (os.contains("win")) {
			return windowsMachineGuid();
		}
		return linuxMachineId();
	}

	/**
	 * The {@code IOPlatformUUID} line from {@code ioreg}, stripped and lowercased exactly as
	 * {@code node-machine-id} does before hashing.
	 */
	private static String darwinPlatformUuid() {
		String output = KiroCommand.output("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
		int keyAt = output.indexOf(IOREG_KEY);
		if (keyAt < 0) {
			return null;
		}
		String line = output.substring(keyAt + IOREG_KEY.length()).split(
				"\n",
				2
		)[0];
		return STRIPPED.matcher(line).replaceAll("").toLowerCase(Locale.ROOT);
	}

	private static String windowsMachineGuid() {
		String output = KiroCommand.output(
				"REG.exe",
				"QUERY",
				"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography",
				"/v",
				"MachineGuid"
		);
		int valueAt = output.indexOf("REG_SZ");
		if (valueAt < 0) {
			return null;
		}
		return output.substring(valueAt + "REG_SZ".length())
		             .replaceAll("\\s", "")
		             .toLowerCase(Locale.ROOT);
	}

	/**
	 * {@code /etc/machine-id}, or the dbus copy on distributions that only ship that one.
	 */
	private static String linuxMachineId() {
		String output = KiroCommand.output(
				"sh",
				"-c",
				"cat /var/lib/dbus/machine-id /etc/machine-id 2>/dev/null | head -n 1"
		);
		return output.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of()
		                .formatHex(
				                MessageDigest.getInstance("SHA-256")
				                             .digest(
						                             value.getBytes(
								                             StandardCharsets.UTF_8
						                             )
				                             )
		                );
	}
}
