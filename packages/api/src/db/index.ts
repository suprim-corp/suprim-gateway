import { Database } from "bun:sqlite"
import { mkdirSync } from "node:fs"
import { dirname } from "node:path"
import { drizzle } from "drizzle-orm/bun-sqlite"
import * as schema from "./schema"

const DB_PATH = "./data/gateway.db"

mkdirSync(dirname(DB_PATH), { recursive: true })

const sqlite = new Database(DB_PATH)
sqlite.exec("PRAGMA journal_mode = WAL;")
sqlite.exec("PRAGMA busy_timeout = 5000;")

export const db = drizzle(sqlite, { schema })
export { schema }
