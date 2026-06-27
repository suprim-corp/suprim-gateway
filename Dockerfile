FROM oven/bun:1.3-alpine AS base
WORKDIR /app

# Install dependencies
FROM base AS deps
COPY package.json bun.lock ./
COPY packages/api/package.json packages/api/
COPY packages/web/package.json packages/web/
COPY packages/shared/package.json packages/shared/
RUN bun install --frozen-lockfile

# Build API
FROM base AS build-api
COPY --from=deps /app/node_modules node_modules
COPY --from=deps /app/packages/api/node_modules packages/api/node_modules
COPY package.json bun.lock ./
COPY packages/shared packages/shared
COPY packages/api packages/api
RUN cd packages/api && bun build src/index.ts --outdir dist --target bun

# Build Web
FROM base AS build-web
COPY --from=deps /app/node_modules node_modules
COPY --from=deps /app/packages/web/node_modules packages/web/node_modules
COPY package.json bun.lock ./
COPY packages/shared packages/shared
COPY packages/web packages/web
RUN cd packages/web && bun run build && \
    rm -rf .next/standalone/node_modules/.bun/@img+sharp-libvips-* \
           .next/standalone/node_modules/.bun/@img+sharp-linux* \
           .next/standalone/node_modules/.bun/@img+sharp-linuxmusl* \
           .next/standalone/node_modules/.bun/sharp@*

# Compress Bun binary
FROM base AS compress
RUN apk add --no-cache upx binutils && \
    cp /usr/local/bin/bun /tmp/bun && \
    strip /tmp/bun && \
    upx --best --lzma /tmp/bun

# Production
FROM alpine:3.21
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && \
    apk add --no-cache libgcc libstdc++ su-exec && \
    rm -rf /var/cache/apk /usr/share/apk /etc/apk && \
    mkdir -p /app/data && chown app:app /app/data

COPY --from=compress /tmp/bun /usr/local/bin/bun

COPY --from=build-api --chown=app:app /app/packages/api/dist packages/api/dist
COPY --from=build-api --chown=app:app /app/packages/api/drizzle packages/api/drizzle

COPY --from=build-web --chown=app:app /app/packages/web/.next/standalone packages/web
COPY --from=build-web --chown=app:app /app/packages/web/.next/static packages/web/packages/web/.next/static
COPY --from=build-web --chown=app:app /app/packages/web/public packages/web/packages/web/public

COPY --chown=app:app entrypoint.sh .
RUN chmod +x entrypoint.sh

EXPOSE 3000

CMD ["./entrypoint.sh"]
