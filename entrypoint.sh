#!/bin/sh
# Fix creds permissions so app user can read/write refreshed tokens
chmod 664 /app/creds/*.json 2>/dev/null
chown app:app /app/creds/*.json 2>/dev/null

# Start API (internal only, not exposed)
cd /app/packages/api && su-exec app bun dist/index.js &
sleep 2

# Start Next.js on port 3000, bind to all interfaces
cd /app/packages/web/packages/web && HOSTNAME=0.0.0.0 PORT=3000 exec su-exec app bun server.js
