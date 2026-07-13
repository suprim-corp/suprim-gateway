package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.proxy.KiroFrameDecoder;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class KiroEventParser {

	private final KiroFrameDecoder decoder = new KiroFrameDecoder();
	private final KiroEventDispatcher dispatcher = new KiroEventDispatcher();

	public List<KiroEvent> feed(byte[] chunk) {
		List<JsonNode> frames = decoder.decode(chunk);
		List<KiroEvent> events = new ArrayList<>();
		for (JsonNode frame : frames) {
			events.addAll(dispatcher.dispatch(frame));
		}
		return events;
	}

	public static List<KiroEvent> parseStream(InputStream input) throws Exception {
		KiroEventParser parser = new KiroEventParser();
		List<KiroEvent> allEvents = new ArrayList<>();
		byte[] buf = new byte[8192];
		int read;
		while ((read = input.read(buf)) != -1) {
			byte[] chunk = new byte[read];
			System.arraycopy(buf, 0, chunk, 0, read);
			allEvents.addAll(parser.feed(chunk));
		}
		return allEvents;
	}
}
