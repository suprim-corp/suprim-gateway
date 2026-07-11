# Suprim Gateway

<p align="center">
  <img src="https://avatars.githubusercontent.com/u/248639477?s=400&u=4f31236a2e82cde0eb0f067921b5182f93bf790f&v=4" width="120"  alt=""/>
</p>

Proxy gateway for LLM providers — OpenAI-compatible API with admin dashboard and virtual key system.

## Supported Providers

| Provider                                                                                                                                                             | Status | Note            |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|-----------------|
| <img src="https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/kiro-color.png" width="16" /> Kiro (AWS Q Developer)      | Active | Use at own risk |
| <img src="https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/openai.png" width="16" /> Codex (OpenAI)                  | Active | Use at own risk |
| <img src="https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/antigravity-color.png" width="16" /> Antigravity (Google) | Active | Use at own risk |

## Stack

| Layer    | Tech                           | Version |
|----------|--------------------------------|---------|
| Runtime  | Java (Corretto)                | 26      |
| Backend  | Spring Boot                    | 4.1.0   |
| Template | Thymeleaf                      | 4.x     |
| Database | SQLite (Flyway migrations)     | JDBC    |
| HTTP     | java.net.http.HttpClient       | stdlib  |
| UI       | Tailwind CSS + HTMX + Chart.js | CDN     |
| Build    | Maven                          | 3.9+    |
| JSON     | Jackson 3                      | 3.x     |
| Tokens   | jtokkit (cl100k_base)          | 1.1.0   |

## Features

- **OpenAI-compatible API** — `/v1/chat/completions`, `/v1/models`
- **Anthropic Messages API** — `/v1/messages` (native Claude SDK support)
- **Streaming SSE** — real-time response streaming
- **Virtual Keys** — create and manage API keys per user, rate limit, model whitelist
- **Dashboard** — Thymeleaf server-rendered UI with HTMX live polling
- **Auth** — supports Kiro Desktop Auth + AWS SSO OIDC + Kiro CLI SQLite
- **Model resolver** — auto-normalizes model names (dots, dashes, date suffixes)
- **Dynamic model registry** — fetches available models from Kiro API, caches locally
- **Token estimation** — jtokkit (cl100k_base) with Claude correction factor
- **Per-model pricing** — cost tracking per request in logs
- **Retry logic** — auto-retry on 403/429/5xx with exponential backoff
- **Proxy chain** — HTTP/SOCKS5 proxy list with automatic failover (geo-bypass)

## Quick Start

```bash
# Clone
git clone https://github.com/sant1ago/suprim-gateway.git
cd suprim-gateway

# Configure
cp .env.example .env
# Edit .env with your credentials

# Build & run
./mvnw spring-boot:run
```

App starts on http://localhost:3001 — serves both dashboard and proxy API.

## Configuration

```env
# Kiro credentials (choose 1 of 3)
KIRO_CREDS_FILE="~/.aws/sso/cache/kiro-auth-token.json"
# or
REFRESH_TOKEN="your_refresh_token"
# or
KIRO_CLI_DB_FILE="~/Library/Application Support/kiro-cli/data.sqlite3"

# Admin
ADMIN_API_KEY="your-admin-secret"

# Optional
KIRO_REGION="us-east-1"
```

## Proxy Configuration

Create `data/proxies.json` to route upstream calls through proxies:

```json
{
	"proxies": [
		"socks5|user:pass@sg-proxy.example.com:1080",
		"http|user:pass@us-proxy.example.com:8080"
	]
}
```

- Format: `scheme|[user:pass@]host:port`
- Supported schemes: `http`, `socks5`
- Order = priority — first proxy is preferred, rest are fallback
- On failure (connection error, timeout) → automatically switches to next proxy
- File missing or empty array → direct connection (no proxy)
- Passwords are masked in all logs

Override file path via env var: `PROXY_FILE="/custom/path/proxies.json"`

## API Endpoints

### Proxy API (OpenAI-compatible)

| Method | Path                   | Auth        | Description               |
|--------|------------------------|-------------|---------------------------|
| GET    | `/health`              | —           | Health check              |
| GET    | `/v1/models`           | Virtual Key | List models               |
| POST   | `/v1/chat/completions` | Virtual Key | Chat completions (OpenAI) |
| POST   | `/v1/messages`         | Virtual Key | Messages (Anthropic)      |
| POST   | `/v1/responses`        | Virtual Key | Responses (OpenAI)        |

### Admin Dashboard

| Path        | Description       |
|-------------|-------------------|
| `/`         | Dashboard (stats) |
| `/logs`     | Request logs      |
| `/keys`     | Virtual key CRUD  |
| `/usage`    | Usage charts      |
| `/settings` | Settings          |
| `/login`    | Login page        |

## Virtual Keys

```bash
# Use virtual key with OpenAI SDK
curl http://localhost:3001/v1/chat/completions \
  -H "Authorization: Bearer sk-kiro-xxxxx" \
  -d '{"model": "claude-sonnet-4-5", "messages": [{"role": "user", "content": "Hello!"}], "stream": true}'
```

Each virtual key has:

- Rate limit (requests/min)
- Model whitelist (restrict which models can be used)
- Usage tracking (request count, tokens used)
- Enable/disable toggle
- Revoke (permanent)

## Supported Models

| Model             | Input $/1M | Output $/1M |
|-------------------|------------|-------------|
| auto              | 3.00       | 15.00       |
| claude-sonnet-4   | 3.00       | 15.00       |
| claude-sonnet-4.5 | 3.00       | 15.00       |
| claude-sonnet-4.6 | 3.00       | 15.00       |
| claude-opus-4     | 5.00       | 25.00       |
| claude-opus-4.5   | 5.00       | 25.00       |
| claude-opus-4.6   | 5.00       | 25.00       |
| claude-haiku-4.5  | 1.00       | 5.00        |
| claude-3.7-sonnet | 3.00       | 15.00       |
| deepseek-v3.2     | 0.62       | 1.85        |
| deepseek-3.2      | 0.62       | 1.85        |
| glm-5             | 1.00       | 3.20        |
| minimax-m2.5      | 0.30       | 1.20        |
| minimax-m2.1      | 0.30       | 1.20        |
| qwen3-coder-next  | 0.15       | 1.20        |

Model names are auto-normalized — `claude-sonnet-4-5`, `claude-sonnet-4.5`, `claude-sonnet-4-5-20250929` all work.

## Kiro CLI Auth (Recommended)

Install and login with [Kiro CLI](https://kiro.dev/docs/cli/installation/) on the host machine:

```bash
# macOS
brew install kiro-cli

# Then authenticate
kiro
```

Gateway reads tokens directly from the CLI's SQLite database and persists refreshed tokens back. Both gateway and
kiro-cli share the same token chain.

Set in `.env`:

```env
KIRO_CLI_DB_FILE="~/Library/Application Support/kiro-cli/data.sqlite3"
```

## Development

```bash
./mvnw spring-boot:run          # Run (port 3001)
./mvnw compile                  # Compile only
./mvnw package -DskipTests      # Build JAR
java -jar target/*.jar          # Run JAR
```

## License

AGPL-3.0 — see [LICENSE](LICENSE)
