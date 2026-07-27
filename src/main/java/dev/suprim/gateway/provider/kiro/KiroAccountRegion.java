package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.provider.StoredAccount;

/**
 * Picks the region an account's control-plane calls should target.
 * <p>
 * The profile ARN wins when present, since it names the region that actually issued the account.
 * The stored region fields come next, and the configured default last.
 */
final class KiroAccountRegion {

	private static final String MODELS_PATH =
			"/ListAvailableModels?origin=AI_EDITOR&maxResults=50";

	private KiroAccountRegion() {}

	/**
	 * The account's region, falling back to {@code configuredDefault} when it names none.
	 */
	static String resolve(StoredAccount account, String configuredDefault) {
		String fromArn = KiroProfileArn.region(account.profileArn());
		if (fromArn != null) {
			return fromArn;
		}
		if (account.apiRegion() != null) {
			return account.apiRegion();
		}
		return account.region() != null ? account.region() : configuredDefault;
	}

	/**
	 * The {@code ListAvailableModels} URL for an account. The default service region is served by
	 * the CodeWhisperer host; every other region goes through its Q host.
	 */
	static String modelsUrl(StoredAccount account, String configuredDefault) {
		String serviceRegion = Kiro.serviceRegion(
				resolve(account, configuredDefault)
		);
		String host = Kiro.DEFAULT_SERVICE_REGION.equals(serviceRegion)
				? Kiro.CODEWHISPERER_HOST
				: Kiro.qHost(serviceRegion);
		return host + MODELS_PATH;
	}
}
