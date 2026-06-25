export const SUPPORTED_MODELS = [
	"auto",
	"claude-sonnet-4",
	"claude-sonnet-4.5",
	"claude-sonnet-4.6",
	"claude-opus-4",
	"claude-opus-4.5",
	"claude-opus-4.6",
	"claude-haiku-4.5",
	"claude-3.7-sonnet",
	"deepseek-v3.2",
	"glm-5",
	"minimax-m2.5",
	"minimax-m2.1",
	"qwen3-coder-next",
] as const

export const HIDDEN_MODELS: Record<string, string> = {
	"claude-3.7-sonnet": "CLAUDE_3_7_SONNET_20250219_V1_0",
}

export const MODEL_ALIASES: Record<string, string> = {
	"auto-kiro": "auto",
}

export const FALLBACK_MODELS = [
	{ modelId: "auto" },
	{ modelId: "claude-sonnet-4" },
	{ modelId: "claude-sonnet-4.5" },
	{ modelId: "claude-sonnet-4.6" },
	{ modelId: "claude-opus-4" },
	{ modelId: "claude-opus-4.5" },
	{ modelId: "claude-opus-4.6" },
	{ modelId: "claude-haiku-4.5" },
	{ modelId: "claude-3.7-sonnet" },
	{ modelId: "deepseek-v3.2" },
	{ modelId: "glm-5" },
	{ modelId: "minimax-m2.5" },
	{ modelId: "minimax-m2.1" },
	{ modelId: "qwen3-coder-next" },
]

export const DEFAULT_REGION = "us-east-1"
