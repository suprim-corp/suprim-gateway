package dev.suprim.gateway.model;

import dev.suprim.gateway.antigravity.AntigravityAuthManager;
import dev.suprim.gateway.config.AppConfig;
import dev.suprim.gateway.proxy.KiroHttpClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class ModelRegistry {

	private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);
	private static final Pattern DOT_VERSION = Pattern.compile(
			"^(claude-(?:sonnet|opus|haiku)-)(\\d+)\\.(\\d+)$");

	private static final List<String> FALLBACK_MODELS = List.of(
			"auto",
			"claude-sonnet-4",
			"claude-sonnet-4.5",
			"claude-sonnet-4.6",
			"claude-opus-4",
			"claude-opus-4.5",
			"claude-opus-4.6",
			"claude-haiku-4.5",
			"claude-3.7-sonnet",
			"deepseek-3.2",
			"glm-5",
			"minimax-m2.5",
			"minimax-m2.1",
			"qwen3-coder-next"
	);

	private static final Set<String> HIDDEN_MODELS = Set.of(
			"auto",
			"claude-3.7-sonnet"
	);

	private final KiroHttpClient client;
	private final AppConfig config;
	private final AntigravityAuthManager antigravityAuthManager;
	private final List<String> cachedModels = new CopyOnWriteArrayList<>(
			FALLBACK_MODELS);

	@PostConstruct
	void init() {
		refreshModels();
	}

	@Scheduled(fixedDelay = 300_000)
	public void refreshModels() {
		try {
			String url = client.getListModelsUrl();
			log.info("[Models] Fetching from {}", url);
			KiroHttpClient.KiroResponse res = client.request(
					"GET",
					url,
					null,
					false
			);
			if (res.status() == 200) {
				try (InputStream is = res.body()) {
					String json = new String(is.readAllBytes());
					List<String> ids = parseModelIds(json);
					if (!ids.isEmpty()) {
						cachedModels.clear();
						cachedModels.addAll(ids);
						log.info("[Models] {} fetched: {}", ids.size(), ids);
						return;
					}
				}
			}
			log.warn(
					"[Models] API returned status {}, using fallbacks",
					res.status()
			);
		} catch (Exception e) {
			log.warn(
					"[Models] Failed to fetch: {}, using fallbacks",
					e.getMessage()
			);
		}
		if (cachedModels.isEmpty()) {
			cachedModels.addAll(FALLBACK_MODELS);
		}
	}

	public List<String> getAvailableModels() {
		Set<String> disabled = config.disabledModelsSet();
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String id : cachedModels) {
			if (disabled.contains(id) || HIDDEN_MODELS.contains(id)) continue;
			result.add(id);
			Matcher m = DOT_VERSION.matcher(id);
			if (m.matches()) {
				String hyphenated = m.group(1) + m.group(2) + "-" + m.group(3);
				if (!disabled.contains(hyphenated)) result.add(hyphenated);
			}
		}
		return new ArrayList<>(result);
	}

	public List<String> getAntigravityModels() {
		if (!antigravityAuthManager.isConnected()) return List.of();
		try {
			return antigravityAuthManager.listModels().stream()
			                             .map(m -> (String) m.get("id"))
			                             .toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	public List<Map<String, Object>> getAllModelsForApi() {
		List<Map<String, Object>> result = new ArrayList<>();
		long now = System.currentTimeMillis() / 1000;

		getAvailableModels().forEach(id ->
				result.add(Map.of("id", id, "object", "model", "created", now, "owned_by", "kiro"))
		);

		getAntigravityModels().forEach(id ->
				result.add(Map.of("id", id, "object", "model", "created", now, "owned_by", "antigravity"))
		);

		return result;
	}

	private List<String> parseModelIds(String json) {
		List<String> ids = new ArrayList<>();
		int idx = 0;
		String key = "\"modelId\"";
		while ((idx = json.indexOf(key, idx)) != -1) {
			int colon = json.indexOf(':', idx + key.length());
			int quote1 = json.indexOf('"', colon + 1);
			int quote2 = json.indexOf('"', quote1 + 1);
			if (quote1 != -1 && quote2 != -1) {
				ids.add(json.substring(quote1 + 1, quote2));
			}
			idx = quote2 + 1;
		}
		return ids;
	}
}
