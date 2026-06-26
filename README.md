# Kiro Gateway

Proxy gateway cho Kiro API (AWS Q Developer) — tương thích OpenAI API, có dashboard quản lý và hệ thống virtual key.

## Stack

| Layer         | Tech                     | Version |
|---------------|--------------------------|---------|
| Runtime       | Bun                      | 1.3.14+ |
| Backend       | Elysia                   | 1.4.x   |
| Frontend      | Next.js                  | 16.2    |
| Database      | SQLite (bun:sqlite)      | native  |
| ORM           | Drizzle                  | 1.x     |
| UI            | shadcn/ui (CLI v4, Luma) | latest  |
| Data fetching | TanStack Query           | v5      |
| Styling       | Tailwind CSS             | 4.x     |
| Linter        | Biome                    | latest  |
| Test          | Vitest                   | latest  |
| Monorepo      | Turborepo                | latest  |

## Features

- **OpenAI-compatible API** — `/v1/chat/completions`, `/v1/models`
- **Anthropic Messages API** — `/v1/messages` (native Claude SDK support)
- **Responses API** — `/v1/responses` (OpenAI Responses format)
- **Streaming SSE** — real-time response streaming
- **Multi-account** — nhiều Kiro accounts với failover tự động
- **Virtual Keys** — tạo và quản lý API keys cho users, rate limit, model whitelist
- **Dashboard** — web UI để xem logs, quản lý accounts, keys, health monitoring
- **Auth** — hỗ trợ Kiro Desktop Auth + AWS SSO OIDC
- **Model resolver** — tự normalize tên model (dots, dashes, date suffixes)
- **Retry logic** — tự retry khi 403/429/5xx với exponential backoff

## Project Structure

```
kiro-gateway/
├── package.json              # Bun workspaces
├── turbo.json
├── tsconfig.base.json
├── .env.example
├── packages/
│   ├── api/                  # Elysia backend
│   │   └── src/
│   │       ├── index.ts      # Entry point
│   │       ├── config.ts     # Env config (Zod)
│   │       ├── db/           # Drizzle schema + migrations
│   │       ├── auth/         # Token lifecycle
│   │       ├── kiro/         # HTTP client, headers, stream parser
│   │       ├── models/       # Name normalization + resolution
│   │       ├── streaming/    # Kiro → OpenAI SSE converter
│   │       ├── converters/   # OpenAI request → Kiro payload
│   │       ├── virtual-keys/ # CRUD, rate limiter, auth middleware
│   │       ├── routes/       # openai.ts, admin.ts, health.ts
│   │       └── logging/      # Request logger
│   ├── web/                  # Next.js 16.2 dashboard
│   │   └── src/app/
│   │       ├── page.tsx            # Dashboard (stats overview)
│   │       ├── logs/page.tsx       # Request logs
│   │       ├── accounts/page.tsx   # Account management
│   │       ├── keys/page.tsx       # Virtual key management
│   │       └── settings/page.tsx   # Settings
│   └── shared/               # Shared types + constants
```

## Quick Start

```bash
# Clone
git clone https://github.com/sant1ago/kiro-gateway.git
cd kiro-gateway

# Install
bun install

# Configure
cp .env.example .env
bun run generate:key    # Generate ADMIN_API_KEY and write to .env
# Edit .env với credentials của bạn

# Dev
bun dev           # Start cả backend + frontend
bun dev:api       # Chỉ backend (:3001, internal)
bun dev:web       # Dashboard + API proxy (:3000)
```

## Configuration

```env
# Kiro credentials (chọn 1 trong 3)
KIRO_CREDS_FILE="~/.aws/sso/cache/kiro-auth-token.json"
# hoặc
REFRESH_TOKEN="your_refresh_token"
# hoặc
KIRO_CLI_DB_FILE="~/.local/share/kiro-cli/data.sqlite3"

# Admin
ADMIN_API_KEY="your-admin-secret"    # or: bun run generate:key

# Optional
KIRO_REGION="us-east-1"
VPN_PROXY_URL=""
HOST="127.0.0.1"                     # 0.0.0.0 for container
```

## API Endpoints

### Proxy API (OpenAI-compatible)

| Method | Path                   | Auth        | Description              |
|--------|------------------------|-------------|--------------------------|
| GET    | `/health`              | —           | Health check             |
| GET    | `/v1/models`           | Virtual Key | List models              |
| POST   | `/v1/chat/completions` | Virtual Key | Chat completions (OpenAI)|
| POST   | `/v1/messages`         | Virtual Key | Messages (Anthropic)     |
| POST   | `/v1/responses`        | Virtual Key | Responses (OpenAI)       |

### Admin API (Dashboard)

| Method                | Path              | Auth      | Description         |
|-----------------------|-------------------|-----------|---------------------|
| GET                   | `/admin/stats`    | Admin Key | Dashboard stats     |
| GET                   | `/admin/logs`     | Admin Key | Request logs        |
| GET/POST              | `/admin/accounts` | Admin Key | Manage accounts     |
| GET/POST/PATCH/DELETE | `/admin/keys`     | Admin Key | Manage virtual keys |

## Virtual Keys

Virtual keys cho phép bạn tạo nhiều API keys cho users khác nhau:

```bash
# Tạo key qua dashboard hoặc API
curl -X POST http://localhost:3000/api/admin/keys \
  -H "Authorization: Bearer $ADMIN_API_KEY" \
  -d '{"name": "user-1", "rateLimitPerMin": 30, "allowedModels": ["claude-sonnet-4.5"]}'

# Sử dụng virtual key
curl http://localhost:3000/v1/chat/completions \
  -H "Authorization: Bearer sk-kiro-xxxxx" \
  -d '{"model": "claude-sonnet-4-5", "messages": [{"role": "user", "content": "Hello!"}], "stream": true}'
```

Mỗi virtual key có:

- Rate limit (requests/phút)
- Model whitelist (restrict models nào được dùng)
- Usage tracking (request count, tokens used)
- Enable/disable toggle

## Supported Models

| Model             | Description                |
|-------------------|----------------------------|
| claude-sonnet-4.5 | Balanced, great for coding |
| claude-haiku-4.5  | Fast, cheap                |
| claude-sonnet-4   | Previous gen               |
| deepseek-v3.2     | Open MoE 685B              |
| glm-5             | Open MoE 744B              |
| qwen3-coder-next  | Coding-focused             |

Model names tự động normalize — `claude-sonnet-4-5`, `claude-sonnet-4.5`, `claude-sonnet-4-5-20250929` đều hoạt động.

## Docker

```bash
# Build & run
docker compose up -d

# Rebuild
docker compose up -d --build

# Logs
docker compose logs -f
```

Image: ~57MB (Alpine + UPX-compressed Bun). Chỉ expose port 3000 (Next.js), API chạy internal trên 3001.

SQLite data persist qua volume `./data`. Cần set `DATABASE_PATH` trong `.env`:

```env
DATABASE_PATH=/app/data/gateway.db
```

Port expose configurable qua `WEB_PORT` (default 3000):

```env
WEB_PORT=8080
```

## Development

```bash
bun test          # Run tests
bun lint          # Biome lint + format check
bun build         # Build all packages
bun db:generate   # Generate Drizzle migrations
bun db:migrate    # Run migrations
bun generate:key  # Generate admin key → .env
```

## License

AGPL-3.0 — xem [LICENSE](LICENSE)
