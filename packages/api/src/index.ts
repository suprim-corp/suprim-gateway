import { bearer } from "@elysiajs/bearer"
import { cors } from "@elysiajs/cors"
import { Elysia } from "elysia"
import { env } from "./config"
import { runMigrations } from "./db/migrate"
import { logger } from "./logging/logger"
import { adminRoutes } from "./routes/admin"
import { healthRoutes } from "./routes/health"
import { completionsRoutes } from "./routes/completions"
import { responsesRoutes } from "./routes/responses"
import { anthropicRoutes } from "./routes/anthropic"
import { MODEL_CACHE_TTL, refreshModelCache } from "./routes/shared"

runMigrations()

const app = new Elysia()
	.use(cors())
	.use(bearer())
	.use(healthRoutes)
	.use(completionsRoutes)
	.use(responsesRoutes)
	.use(anthropicRoutes)
	.use(adminRoutes)
	.listen(3001)

logger.info(`Kiro Gateway | API :3001 | Dashboard :3000 | pid:${process.pid}`)
refreshModelCache().then(() => {})
setInterval(refreshModelCache, MODEL_CACHE_TTL)

export type App = typeof app
