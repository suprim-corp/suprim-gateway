package dev.suprim.gateway.utils;

import dev.suprim.gateway.virtualkey.VirtualKey;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class RequestContext {

	private RequestContext() {}

	public static VirtualKey resolveKey() {
		Authentication auth = SecurityContextHolder.getContext()
		                                           .getAuthentication();
		if (auth != null && auth.getDetails() instanceof VirtualKey k) {
			return k;
		}

		return null;
	}

	public static String clientIp(HttpServletRequest req) {
		String xff = req.getHeader("X-Forwarded-For");
		return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
	}
}
