import { Elysia } from "elysia"
import { env } from "../../config"
import { type AuthResult, resolveAuth } from "../../virtual-keys"
import { modelResolver } from "../shared"
import { handleChatCompletion } from "./handler"

export { type ChatCompletionResponse } from "./types"

export const completionsRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers, request, server }) => {
		const authResult = await resolveAuth(
			headers.authorization ?? headers["x-api-key"],
		)
		const clientIp = headers["x-forwarded-for"]?.split(",")[0]?.trim()
			?? headers["x-real-ip"]
			?? server?.requestIP(request)?.address
		return { authResult, clientIp }
	})
	.onBeforeHandle(({ authResult, set }) => {
		if (!authResult) {
			set.status = 401
			return {
				error: {
					message: "Invalid or missing API Key",
					type: "auth_error",
				},
			}
		}
	})
	.get("/models", () => {
		const allModels = modelResolver
			.getAvailableModels()
			.filter((id) => !env.DISABLED_MODELS.includes(id))

		return {
			object: "list",
			data: allModels.map((id) => ({
				id,
				object: "model",
				created: 1700000000,
				owned_by: "kiro",
			})),
		}
	})
	.post("/chat/completions", ({ body, set, authResult, clientIp }) => {
		return handleChatCompletion(body, set, authResult as AuthResult, clientIp)
	})
