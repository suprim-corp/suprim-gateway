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
# Edit .env với credentials của bạn

# Dev
bun dev           # Start cả backend + frontend
bun dev:api       # Chỉ backend (:3001)
bun dev:web       # Chỉ dashboard (:3000)
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
ADMIN_API_KEY="your-admin-secret"

# Optional
KIRO_REGION="us-east-1"
VPN_PROXY_URL=""
```

## API Endpoints

### Proxy API (OpenAI-compatible)

| Method | Path                   | Auth        | Description      |
|--------|------------------------|-------------|------------------|
| GET    | `/health`              | —           | Health check     |
| GET    | `/v1/models`           | Virtual Key | List models      |
| POST   | `/v1/chat/completions` | Virtual Key | Chat completions |

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
curl -X POST http://localhost:3001/admin/keys \
  -H "Authorization: Bearer $ADMIN_API_KEY" \
  -d '{"name": "user-1", "rateLimitPerMin": 30, "allowedModels": ["claude-sonnet-4.5"]}'

# Sử dụng virtual key
curl http://localhost:3001/v1/chat/completions \
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

## Development

```bash
bun test          # Run tests
bun lint          # Biome lint + format check
bun build         # Build all packages
bun db:generate   # Generate Drizzle migrations
bun db:migrate    # Run migrations
```

## License

AGPL-3.0 — xem [LICENSE](LICENSE)
