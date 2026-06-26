import { Database } from "bun:sqlite"
import { mkdirSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { drizzle } from "drizzle-orm/bun-sqlite"
import * as schema from "./schema"

const DB_PATH = process.env.DATABASE_PATH ?? resolve(import.meta.dir, "../../data/gateway.db")

mkdirSync(dirname(DB_PATH), { recursive: true })

const sqlite = new Database(DB_PATH)
sqlite.run("PRAGMA journal_mode = WAL;")
sqlite.run("PRAGMA busy_timeout = 5000;")
try { sqlite.run("ALTER TABLE virtual_keys ADD COLUMN revoked_at integer;") } catch {}

export const db = drizzle(sqlite, { schema })
export { schema }
