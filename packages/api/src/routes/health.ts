import { Elysia } from "elysia"

const startedAt = Date.now()

export const healthRoutes = new Elysia()
	.get("/", () => ({
		status: "ok",
		message: "Kiro Gateway is running",
		version: "0.1.0",
	}))
	.get("/health", () => ({
		status: "healthy",
		timestamp: new Date().toISOString(),
		version: "0.1.0",
		uptime: Math.floor((Date.now() - startedAt) / 1000),
	}))
