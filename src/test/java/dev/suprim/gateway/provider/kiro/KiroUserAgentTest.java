package dev.suprim.gateway.provider.kiro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembled user-agent strings, pinned against the shape Kiro IDE's AWS SDK produces.
 * <p>
 * The IDE builds these from name/version pairs and escapes each one, so what matters is the pair
 * order, the separators, and the escaping — not a single literal string, since the OS version and
 * machine id vary per host.
 */
class KiroUserAgentTest {

	@Test
	void streaming_startsWithSdkPairAndUaMetadata() {
		String userAgent = KiroUserAgent.streaming();

		assertTrue(
				userAgent.startsWith("aws-sdk-js/1.0.44 ua/2.1 os/"),
				userAgent
		);
	}

	/** The SDK lowercases an {@code api} pair's name, so the service id must arrive lowercased. */
	@Test
	void streaming_lowercasesTheApiPair() {
		assertTrue(
				KiroUserAgent.streaming()
				             .contains(" api/codewhispererstreaming#1.0.44 "),
				KiroUserAgent.streaming()
		);
	}

	@Test
	void controlPlane_namesTheRuntimeClient() {
		String userAgent = KiroUserAgent.controlPlane();

		assertTrue(userAgent.startsWith("aws-sdk-js/1.0.0 "), userAgent);
		assertTrue(
				userAgent.contains(" api/codewhispererruntime#1.0.0 "),
				userAgent
		);
	}

	/**
	 * The IDE's pair is one space-separated name with no version, so escaping turns both spaces
	 * into dashes. This is why the value reads {@code KiroIDE-<version>-<machineId>}.
	 */
	@Test
	void userAgent_endsWithEscapedIdePair() {
		String userAgent = KiroUserAgent.streaming();
		String idePair = userAgent.substring(userAgent.lastIndexOf(' ') + 1);

		assertTrue(idePair.startsWith("KiroIDE-1.0.228-"), idePair);
		assertFalse(idePair.contains(" "), idePair);
	}

	/** Version pairs join with {@code #}, and the runtime pair names Node, not the JVM. */
	@Test
	void userAgent_reportsNodeRuntimeAndLanguage() {
		String userAgent = KiroUserAgent.streaming();

		assertTrue(userAgent.contains(" lang/js "), userAgent);
		assertTrue(userAgent.contains(" md/nodejs#22.22.0 "), userAgent);
	}

	/** Node reports its platform name, so macOS has to appear as {@code darwin}. */
	@Test
	void userAgent_reportsNodeOsFamily() {
		String os = System.getProperty("os.name").toLowerCase();
		String expected = os.contains("mac")
				? "darwin"
				: os.contains("win") ? "win32" : "linux";

		assertTrue(
				KiroUserAgent.streaming().contains(" os/" + expected + "#"),
				KiroUserAgent.streaming()
		);
	}

	/**
	 * The os pair carries the kernel release, as Node's {@code os.release()} does. On macOS that is
	 * the Darwin version from {@code uname -r}, which differs from the JVM's {@code os.version}
	 * product version — sending the product version would not match the IDE.
	 */
	@Test
	void userAgent_reportsKernelReleaseNotProductVersion() throws Exception {
		String os = System.getProperty("os.name").toLowerCase();
		if (!os.contains("mac")) {
			return;
		}
		Process uname = new ProcessBuilder("uname", "-r").start();
		String kernel = new String(uname.getInputStream().readAllBytes()).trim();
		uname.waitFor();

		assertTrue(
				KiroUserAgent.streaming().contains(" os/darwin#" + kernel + " "),
				KiroUserAgent.streaming() + " | want kernel " + kernel
		);
	}

	/**
	 * {@code x-amz-user-agent} is not the full string: the SDK narrows it to the {@code aws-sdk-*}
	 * pairs plus the caller's own pair.
	 */
	@Test
	void amzUserAgent_carriesOnlySdkAndIdePairs() {
		String amz = KiroUserAgent.amzStreaming();

		assertTrue(amz.startsWith("aws-sdk-js/1.0.44 KiroIDE-1.0.228-"), amz);
		assertFalse(amz.contains("ua/2.1"), amz);
		assertFalse(amz.contains("lang/js"), amz);
		assertFalse(amz.contains("api/"), amz);
	}

	@Test
	void amzControlPlane_namesTheRuntimeClientVersion() {
		assertTrue(
				KiroUserAgent.amzControlPlane()
				             .startsWith("aws-sdk-js/1.0.0 KiroIDE-"),
				KiroUserAgent.amzControlPlane()
		);
	}

	/** The machine id identifies the host, so it must not vary between requests. */
	@Test
	void machineId_isStableAcrossCalls() {
		assertEquals(KiroMachineId.get(), KiroMachineId.get());
	}

	/**
	 * A resolved machine id is a SHA-256 hex digest. The fallback the IDE uses when its own lookup
	 * fails is accepted too, since a sandboxed host cannot run {@code ioreg}.
	 */
	@Test
	void machineId_isAHexDigestOrTheDocumentedFallback() {
		String machineId = KiroMachineId.get();

		assertTrue(
				machineId.matches("[0-9a-f]{64}") ||
				machineId.equals("UNDETERMINED_MACHINE_ID"),
				machineId
		);
	}
}
