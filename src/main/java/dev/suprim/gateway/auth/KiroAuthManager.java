package dev.suprim.gateway.auth;

import dev.suprim.gateway.config.AppConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.node.ArrayNode;

@Component
public class KiroAuthManager {

    private static final Logger log = LoggerFactory.getLogger(KiroAuthManager.class);
    private static final long REFRESH_COOLDOWN_MS = 60_000;

    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ReentrantLock refreshLock = new ReentrantLock();

    private String accessToken;
    private String refreshToken;
    private Instant expiresAt;
    private String profileArn;
    private String clientId;
    private String clientSecret;
    private String[] scopes;
    private KiroCredentials.AuthType authType = KiroCredentials.AuthType.KIRO_DESKTOP;
    private long lastRefreshFailure = 0;
    private String credSourceType;
    private String credSourcePath;

    KiroAuthManager(AppConfig config) {
        this.config = config;
    }

    @PostConstruct
    void init() {
        this.profileArn = config.profileArn();
        if (config.cliDbFile() != null && !config.cliDbFile().isBlank()) {
            credSourceType = "sqlite";
            credSourcePath = config.cliDbFile();
            loadFromSqlite(resolvePath(credSourcePath));
        } else if (config.credsFile() != null && !config.credsFile().isBlank()) {
            credSourceType = "json";
            credSourcePath = config.credsFile();
            loadFromJson(resolvePath(credSourcePath));
        } else if (config.refreshToken() != null && !config.refreshToken().isBlank()) {
            this.refreshToken = config.refreshToken();
        }
        detectAuthType();
        log.info("[Auth] Initialized: type={}, region={}, apiRegion={}", authType, config.region(), config.apiRegion());
    }

    public String getApiHost() {
        return "https://runtime." + config.apiRegion() + ".kiro.dev";
    }

    public String getQHost() {
        return "https://q." + config.apiRegion() + ".amazonaws.com";
    }

    public String getAccessToken() throws Exception {
        if (accessToken != null && expiresAt != null && Instant.now().isBefore(expiresAt.minusSeconds(600))) {
            return accessToken;
        }
        refresh();
        return accessToken;
    }

    public void forceRefresh() throws Exception {
        refresh();
    }

    public String getProfileArn() {
        return profileArn;
    }

    String getRegion() {
        return config.region();
    }

    String getApiRegion() {
        return config.apiRegion();
    }

    private void refresh() throws Exception {
        refreshLock.lock();
        try {
            if (accessToken != null && expiresAt != null && Instant.now().isBefore(expiresAt.minusSeconds(600))) {
                return;
            }
            if (System.currentTimeMillis() - lastRefreshFailure < REFRESH_COOLDOWN_MS) {
                throw new RuntimeException("Token refresh on cooldown");
            }

            if (credSourceType != null) {
                if ("sqlite".equals(credSourceType)) loadFromSqlite(resolvePath(credSourcePath));
                else if ("json".equals(credSourceType)) loadFromJson(resolvePath(credSourcePath));
                if (accessToken != null && expiresAt != null && Instant.now().isBefore(expiresAt.minusSeconds(600))) {
                    return;
                }
            }

            log.info("[Auth] Refreshing token via {}", authType);
            if (authType == KiroCredentials.AuthType.KIRO_DESKTOP) {
                refreshDesktop();
            } else {
                refreshSsoOidc();
            }
        } catch (Exception e) {
            lastRefreshFailure = System.currentTimeMillis();
            throw e;
        } finally {
            refreshLock.unlock();
        }
    }

    private void refreshDesktop() throws Exception {
        String url = "https://prod." + config.region() + ".auth.desktop.kiro.dev/refreshToken";
        String body = mapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String responseBody = response.body();
            throw new RuntimeException("Desktop refresh failed (" + response.statusCode() + "): " + responseBody.substring(0, Math.min(300, responseBody.length())));
        }
        JsonNode json = mapper.readTree(response.body());
        this.accessToken = json.get("accessToken").asText();
        if (json.has("refreshToken")) this.refreshToken = json.get("refreshToken").asText();
        if (json.has("expiresAt")) this.expiresAt = Instant.parse(json.get("expiresAt").asText());
        saveBackToSource();
    }

    private void refreshSsoOidc() throws Exception {
        String url = "https://oidc." + config.region() + ".amazonaws.com/token";
        ObjectNode payload = mapper.createObjectNode();
        payload.put("grantType", "refresh_token");
        payload.put("clientId", clientId);
        payload.put("clientSecret", clientSecret);
        payload.put("refreshToken", refreshToken);
        if (scopes != null && scopes.length > 0) {
            ArrayNode arr = payload.putArray("scope");
            for (String s : scopes) arr.add(s);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String responseBody = response.body();
            throw new RuntimeException("SSO OIDC refresh failed (" + response.statusCode() + "): " + responseBody.substring(0, Math.min(300, responseBody.length())));
        }
        JsonNode json = mapper.readTree(response.body());
        this.accessToken = json.get("accessToken").asText();
        if (json.has("refreshToken")) this.refreshToken = json.get("refreshToken").asText();
        int expiresIn = json.has("expiresIn") ? json.get("expiresIn").asInt() : 3600;
        this.expiresAt = Instant.now().plusSeconds(expiresIn);
        saveBackToSource();
    }

    private void loadFromJson(Path path) {
        try {
            if (!Files.exists(path)) { log.warn("[Auth] JSON creds not found: {}", path); return; }
            JsonNode json = mapper.readTree(Files.readString(path));
            this.accessToken = textOrNull(json, "accessToken");
            this.refreshToken = textOrNull(json, "refreshToken");
            if (json.has("expiresAt")) this.expiresAt = Instant.parse(json.get("expiresAt").asText());
            if (json.has("profileArn") && this.profileArn == null) this.profileArn = json.get("profileArn").asText();
            this.clientId = textOrNull(json, "clientId");
            this.clientSecret = textOrNull(json, "clientSecret");
        } catch (Exception e) {
            log.error("[Auth] Failed to load JSON creds: {}", e.getMessage());
        }
    }

    private void loadFromSqlite(Path path) {
        try {
            if (!Files.exists(path)) { log.warn("[Auth] SQLite DB not found: {}", path); return; }
            String jdbcUrl = "jdbc:sqlite:" + path;
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(PASSIVE)");
                String[] tokenKeys = {"kirocli:social:token", "kirocli:odic:token", "codewhisperer:odic:token"};
                for (String key : tokenKeys) {
                    try (ResultSet rs = stmt.executeQuery("SELECT value FROM auth_kv WHERE key = '" + key + "'")) {
                        if (rs.next()) {
                            JsonNode json = mapper.readTree(rs.getString("value"));
                            this.accessToken = textOrNull(json, "accessToken");
                            this.refreshToken = textOrNull(json, "refreshToken");
                            if (json.has("expiresAt")) this.expiresAt = Instant.parse(json.get("expiresAt").asText());
                            this.clientId = textOrNull(json, "clientId");
                            this.clientSecret = textOrNull(json, "clientSecret");
                            if (this.accessToken != null) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Auth] Failed to load SQLite creds: {}", e.getMessage());
        }
    }

    private void saveBackToSource() {
        if (credSourceType == null) return;
        try {
            if ("json".equals(credSourceType)) {
                Path path = resolvePath(credSourcePath);
                JsonNode json = Files.exists(path) ? mapper.readTree(Files.readString(path)) : mapper.createObjectNode();
                ObjectNode obj = (ObjectNode) json;
                obj.put("accessToken", accessToken);
                obj.put("refreshToken", refreshToken);
                if (expiresAt != null) obj.put("expiresAt", expiresAt.toString());
                Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
            } else if ("sqlite".equals(credSourceType)) {
                Path path = resolvePath(credSourcePath);
                String jdbcUrl = "jdbc:sqlite:" + path;
                try (Connection conn = DriverManager.getConnection(jdbcUrl);
                     PreparedStatement stmt = conn.prepareStatement("UPDATE auth_kv SET value = ? WHERE key LIKE '%token%' AND value LIKE '%accessToken%'")) {
                    ObjectNode obj = mapper.createObjectNode();
                    obj.put("accessToken", accessToken);
                    obj.put("refreshToken", refreshToken);
                    if (expiresAt != null) obj.put("expiresAt", expiresAt.toString());
                    if (clientId != null) obj.put("clientId", clientId);
                    if (clientSecret != null) obj.put("clientSecret", clientSecret);
                    stmt.setString(1, mapper.writeValueAsString(obj));
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("[Auth] Failed to save refreshed token back: {}", e.getMessage());
        }
    }

    private void detectAuthType() {
        if (clientId != null && clientSecret != null) {
            this.authType = KiroCredentials.AuthType.AWS_SSO_OIDC;
        } else {
            this.authType = KiroCredentials.AuthType.KIRO_DESKTOP;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static Path resolvePath(String path) {
        if (path.startsWith("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
