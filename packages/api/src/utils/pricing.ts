// per 1M tokens, USD
interface ModelPricing {
	input: number
	output: number
}

// per 1M tokens, USD — sourced from AWS Bedrock pricing
const PRICING: Record<string, ModelPricing> = {
	"auto": { input: 3, output: 15 },
	"claude-sonnet-4": { input: 3, output: 15 },
	"claude-sonnet-4.5": { input: 3, output: 15 },
	"claude-sonnet-4.6": { input: 3, output: 15 },
	"claude-opus-4": { input: 5, output: 25 },
	"claude-opus-4.5": { input: 5, output: 25 },
	"claude-opus-4.6": { input: 5, output: 25 },
	"claude-haiku-4.5": { input: 1, output: 5 },
	"claude-3.7-sonnet": { input: 3, output: 15 },
	"deepseek-v3.2": { input: 0.62, output: 1.85 },
	"deepseek-3.2": { input: 0.62, output: 1.85 },
	"glm-5": { input: 1, output: 3.2 },
	"minimax-m2.5": { input: 0.3, output: 1.2 },
	"minimax-m2.1": { input: 0.3, output: 1.2 },
	"qwen3-coder-next": { input: 0.15, output: 1.2 },
}

const MODEL_ALIASES: Record<string, string> = {
	"claude-opus-4-6": "claude-opus-4.6",
	"claude-opus-4-5": "claude-opus-4.5",
	"claude-sonnet-4-6": "claude-sonnet-4.6",
	"claude-sonnet-4-5": "claude-sonnet-4.5",
	"claude-haiku-4-5": "claude-haiku-4.5",
}

const DEFAULT_PRICING: ModelPricing = { input: 3, output: 15 }

export function resolveModelAlias(model: string): string {
	return MODEL_ALIASES[model] ?? model
}

export function getModelPricing(model: string): ModelPricing {
	const resolved = resolveModelAlias(model)
	return PRICING[resolved] ?? DEFAULT_PRICING
}

export function calculateCost(model: string, promptTokens: number, completionTokens: number): number {
	const p = getModelPricing(model)
	return (promptTokens * p.input + completionTokens * p.output) / 1_000_000
}
