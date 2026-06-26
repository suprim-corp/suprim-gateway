import { bearer } from "@elysiajs/bearer"
import { cors } from "@elysiajs/cors"
import { Elysia } from "elysia"
import { env } from "./config"
import { runMigrations } from "./db/migrate"
import { logger } from "./logging/logger"
import { adminRoutes } from "./routes/admin"
import { healthRoutes } from "./routes/health"
import {
	MODEL_CACHE_TTL,
	openaiRoutes,
	refreshModelCache,
} from "./routes/openai"
import { anthropicRoutes } from "./routes/anthropic"
import { responsesRoutes } from "./routes/responses"

runMigrations()

const app = new Elysia()
	.use(cors())
	.use(bearer())
	.use(healthRoutes)
	.use(openaiRoutes)
	.use(responsesRoutes)
	.use(anthropicRoutes)
	.use(adminRoutes)
	.listen(env.PORT)

logger.info(`Kiro Gateway | API :${env.PORT} | Dashboard :3000 | pid:${process.pid}`)
refreshModelCache().then(() => {})
setInterval(refreshModelCache, MODEL_CACHE_TTL)

export type App = typeof app
