package dev.suprim.gateway.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProxyListLoader {

	private static final Logger log = LoggerFactory.getLogger(ProxyListLoader.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	private ProxyListLoader() {}

	public static List<ProxyEntry> load(Path file) {
		if (!Files.exists(file)) {
			log.info(
					"[Proxy] No proxy file found at {}, using direct connection",
					file
			);
			return Collections.emptyList();
		}

		try {
			String content = Files.readString(file);
			JsonNode root = mapper.readTree(content);
			JsonNode proxiesNode = root.get("proxies");

			if (proxiesNode == null || !proxiesNode.isArray()) {
				log.warn(
						"[Proxy] No 'proxies' array in {}, using direct connection",
						file
				);
				return Collections.emptyList();
			}

			List<ProxyEntry> entries = new ArrayList<>();
			for (JsonNode node : proxiesNode) {
				entries.add(ProxyEntry.parse(node.asString()));
			}
			return Collections.unmodifiableList(entries);
		} catch (Exception e) {
			log.warn(
					"[Proxy] Failed to parse {}: {}, using direct connection",
					file,
					e.getMessage()
			);
			return Collections.emptyList();
		}
	}
}
