package dev.suprim.gateway.provider.deepseek;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekPowSolverTest {

	@Test
	void hash_emptyInput_matchesReference() {
		String result = DeepSeekPowSolver.hashHex("");
		assertEquals("e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3", result);
	}

	@Test
	void hash_saltWithNonce42_matchesReference() {
		String result = DeepSeekPowSolver.hashHex("testsalt_1700000000_42");
		assertEquals("d4a2ea58c89e40887c933484868380c6f803eaa8dc53a3b9df8e431b921a4f09", result);
	}

	@Test
	void hash_saltWithNonce100000_matchesReference() {
		String result = DeepSeekPowSolver.hashHex("testsalt_1700000000_100000");
		assertEquals("abea2f35796b65486e9be1b36f7878c66cab021e96faa473fdf4decd31f9ba30", result);
	}

	@Test
	void hash_differentSaltWithNonce12345_matchesReference() {
		String result = DeepSeekPowSolver.hashHex("abc123salt_1700000000_12345");
		assertEquals("74b3b7452745b70e85eb32ee7f0a9ec0381d42dd5137b695da915e104fc390e1", result);
	}

	@Test
	void solve_nonce42_found() {
		String challenge = DeepSeekPowSolver.hashHex("testsalt_1700000000_42");
		long result = DeepSeekPowSolver.solve(challenge, "testsalt", 1700000000L, 1000);
		assertEquals(42, result);
	}

	@Test
	void solve_nonce500_found() {
		String challenge = DeepSeekPowSolver.hashHex("testsalt_1700000000_500");
		long result = DeepSeekPowSolver.solve(challenge, "testsalt", 1700000000L, 2000);
		assertEquals(500, result);
	}

	@Test
	void solve_nonce12345_found() {
		String challenge = DeepSeekPowSolver.hashHex("abc123salt_1700000000_12345");
		long result = DeepSeekPowSolver.solve(challenge, "abc123salt", 1700000000L, 20000);
		assertEquals(12345, result);
	}

	@Test
	void solve_notFoundWithinDifficulty_returnsNegative() {
		String challenge = DeepSeekPowSolver.hashHex("testsalt_1700000000_9999");
		long result = DeepSeekPowSolver.solve(challenge, "testsalt", 1700000000L, 100);
		assertEquals(-1, result);
	}

	@Test
	void buildHeader_encodesCorrectly() {
		String header = DeepSeekPowSolver.buildPowHeader(
				"DeepSeekHashV1", "abcd1234", "salt", 777L, "sig", "/api/v0/chat/completion"
		);
		assertNotNull(header);
		String decoded = new String(java.util.Base64.getDecoder().decode(header));
		assertTrue(decoded.contains("\"answer\":777"));
		assertTrue(decoded.contains("\"algorithm\":\"DeepSeekHashV1\""));
		assertTrue(decoded.contains("\"signature\":\"sig\""));
	}

	@Test
	void hash_longInput_exceedsSpongeRate() {
		// Input > 136 bytes triggers absorption loop in deepSeekHashV1
		String longInput = "a".repeat(200);
		String result = DeepSeekPowSolver.hashHex(longInput);
		assertNotNull(result);
		assertEquals(64, result.length());
	}

	@Test
	void solve_longSalt_triggersMultiBlockPath() {
		// Salt 130 chars + "_1700000000_" = 142 char prefix, tailLen=6
		// When nonce has enough digits, totalTail >= 136 triggers else branch
		String longSalt = "s".repeat(130);
		// nonce "0" → totalTail = 6+1 = 7 (still < 136, won't trigger)
		// We need tailLen close to 136. Use salt of 123 chars → prefix = 123 + 12 = 135 chars
		// tailLen = 135, nonce "0" = 1 byte → totalTail = 136 → triggers else
		String salt = "x".repeat(123);
		String prefix = salt + "_1700000000_0";
		String challenge = DeepSeekPowSolver.hashHex(prefix);
		long result = DeepSeekPowSolver.solve(challenge, salt, 1700000000L, 10);
		assertEquals(0, result);
	}

	@Test
	void solve_veryLongSalt_multiBlockAbsorptionInPrefix() {
		// Salt > 136 chars triggers while loop in solve() for prefix absorption
		String salt = "y".repeat(150);
		String prefix = salt + "_1700000000_5";
		String challenge = DeepSeekPowSolver.hashHex(prefix);
		long result = DeepSeekPowSolver.solve(challenge, salt, 1700000000L, 10);
		assertEquals(5, result);
	}

	@Test
	void stateMatchesTarget_allMatch_returnsTrue() throws Exception {
		long[] state = {1L, 2L, 3L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
		byte[] target = toLittleEndianBytes(1L, 2L, 3L, 4L);
		assertTrue(invokeStateMatchesTarget(state, target));
	}

	@Test
	void stateMatchesTarget_firstLongMismatch_returnsFalse() throws Exception {
		long[] state = {99L, 2L, 3L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
		byte[] target = toLittleEndianBytes(1L, 2L, 3L, 4L);
		assertFalse(invokeStateMatchesTarget(state, target));
	}

	@Test
	void stateMatchesTarget_secondLongMismatch_returnsFalse() throws Exception {
		long[] state = {1L, 99L, 3L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
		byte[] target = toLittleEndianBytes(1L, 2L, 3L, 4L);
		assertFalse(invokeStateMatchesTarget(state, target));
	}

	@Test
	void stateMatchesTarget_thirdLongMismatch_returnsFalse() throws Exception {
		long[] state = {1L, 2L, 99L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
		byte[] target = toLittleEndianBytes(1L, 2L, 3L, 4L);
		assertFalse(invokeStateMatchesTarget(state, target));
	}

	@Test
	void stateMatchesTarget_fourthLongMismatch_returnsFalse() throws Exception {
		long[] state = {1L, 2L, 3L, 99L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
		byte[] target = toLittleEndianBytes(1L, 2L, 3L, 4L);
		assertFalse(invokeStateMatchesTarget(state, target));
	}

	private static boolean invokeStateMatchesTarget(long[] s, byte[] target) throws Exception {
		Method method = DeepSeekPowSolver.class.getDeclaredMethod("stateMatchesTarget", long[].class, byte[].class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, s, target);
	}

	private static byte[] toLittleEndianBytes(long a, long b, long c, long d) {
		ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		buf.putLong(a).putLong(b).putLong(c).putLong(d);
		return buf.array();
	}
}
