package dev.suprim.gateway.utils;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class ErrorResponse {

	private ErrorResponse() {}

	public static void openAi(
			HttpServletResponse res,
			int status,
			String message,
			String type
	) throws IOException {
		res.setStatus(status);
		res.setContentType("application/json");
		res.getWriter().write(
				"{\"error\":{\"message\":\"" + message + "\",\"type\":\"" +
				type + "\"}}");
	}

	public static void anthropic(
			HttpServletResponse res,
			int status,
			String message,
			String type
	) throws IOException {
		res.setStatus(status);
		res.setContentType("application/json");
		res.getWriter().write(
				"{\"type\":\"error\",\"error\":{\"type\":\"" + type +
				"\",\"message\":\"" + message + "\"}}");
	}

	public static void rateLimitOpenAi(HttpServletResponse res) throws IOException {
		openAi(res, 429, "Rate limit exceeded", "rate_limit_error");
	}

	public static void rateLimitAnthropic(HttpServletResponse res) throws IOException {
		anthropic(res, 429, "Rate limit exceeded", "rate_limit_error");
	}

	public static void badRequest(
			HttpServletResponse res,
			String message
	) throws IOException {
		openAi(res, 400, message, "invalid_request_error");
	}

	public static void serverError(HttpServletResponse res) throws IOException {
		openAi(res, 500, "Internal server error", "server_error");
	}

	public static void serverErrorAnthropic(HttpServletResponse res) throws IOException {
		anthropic(res, 500, "Internal server error", "api_error");
	}
}
