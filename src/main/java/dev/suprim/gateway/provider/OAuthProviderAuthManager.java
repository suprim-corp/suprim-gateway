package dev.suprim.gateway.provider;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface OAuthProviderAuthManager extends ProviderAuthManager {

	List<Map<String, Object>> listModels() throws IOException;
}
