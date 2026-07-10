package dev.suprim.gateway.logging;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class CachedHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] cachedBody;

	public CachedHttpServletRequest(HttpServletRequest request) throws IOException {
		super(request);
		try (InputStream is = request.getInputStream()) {
			this.cachedBody = is.readAllBytes();
		}
	}

	public String getBody() {
		return new String(cachedBody, StandardCharsets.UTF_8);
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
		return new ServletInputStream() {
			@Override
			public boolean isFinished() {
				return bais.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException();
			}

			@Override
			public int read() {
				return bais.read();
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(
				new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
	}
}
