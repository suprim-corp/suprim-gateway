package dev.suprim.gateway.provider;

import tools.jackson.databind.JsonNode;

/**
 * The one shared fact a usage lookup can report besides figures: the credential itself was
 * rejected.
 * <p>
 * Providers each answer usage in their own shape, and a failed lookup used to be indistinguishable
 * from an upstream outage — both arrived as "no figures". That let the providers page keep showing
 * an account as connected while every request it made was being refused. This flag carries the
 * distinction across the provider boundary so the page can tell the two apart.
 * <p>
 * Only credential rejection belongs here. A timeout or a 5xx fixes itself and must not mark an
 * account as unusable.
 */
public final class UsageFailure {

	/** Key set on a usage map when upstream refused the credential. */
	public static final String UNAUTHORIZED = "unauthorized";

	private UsageFailure() {}

	/** True for the status codes that mean "this credential will not work until re-authorised". */
	public static boolean isUnauthorized(int statusCode) {
		return statusCode == 401 || statusCode == 403;
	}

	/** True when a serialized usage tree carries the rejection flag. */
	public static boolean isUnauthorized(JsonNode usage) {
		return usage != null && usage.path(UNAUTHORIZED).asBoolean(false);
	}
}
