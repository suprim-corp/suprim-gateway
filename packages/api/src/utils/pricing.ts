// per 1M tokens, USD
interface ModelPricing {
	input: number
	output: number
}

const PRICING: Record<string, ModelPricing> = {
	"claude-sonnet-4": { input: 3, output: 15 },
	"claude-sonnet-4.5": { input: 3, output: 15 },
	"claude-sonnet-4.6": { input: 3, output: 15 },
	"claude-opus-4": { input: 15, output: 75 },
	"claude-opus-4.5": { input: 15, output: 75 },
	"claude-opus-4.6": { input: 15, output: 75 },
	"claude-haiku-4.5": { input: 0.8, output: 4 },
	"claude-3.7-sonnet": { input: 3, output: 15 },
	"deepseek-v3.2": { input: 0.27, output: 1.1 },
	"glm-5": { input: 0.5, output: 2 },
	"minimax-m2.5": { input: 0.5, output: 2 },
	"minimax-m2.1": { input: 0.5, output: 2 },
	"qwen3-coder-next": { input: 0.5, output: 2 },
}

const DEFAULT_PRICING: ModelPricing = { input: 3, output: 15 }

export function getModelPricing(model: string): ModelPricing {
	return PRICING[model] ?? DEFAULT_PRICING
}

export function calculateCost(model: string, promptTokens: number, completionTokens: number): number {
	const p = getModelPricing(model)
	return (promptTokens * p.input + completionTokens * p.output) / 1_000_000
}
