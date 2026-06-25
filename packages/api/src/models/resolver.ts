import { HIDDEN_MODELS, MODEL_ALIASES } from "@kiro-gateway/shared"

/**
 * Normalize model name to canonical form.
 * Handles: dots vs dashes, date suffixes, context window suffixes, legacy/inverted formats.
 */
export function normalizeModelName(model: string): string {
	let name = model.trim().toLowerCase()

	// 1. Strip context window suffix: [1m], [200k], etc.
	name = name.replace(/\s*\[\d+[km]?\]$/i, "")

	// 2. Handle inverted format with effort suffix: claude-4.5-opus-high → claude-opus-4.5
	const invertedMatch = name.match(
		/^(claude)-(\d+(?:\.\d+)?)-(\w+?)(?:-(low|medium|high|xhigh))?$/,
	)
	if (invertedMatch) {
		const [, prefix, version, variant, _effort] = invertedMatch
		name = `${prefix}-${variant}-${version}`
	}

	// 3. Handle legacy format: claude-3-7-sonnet → claude-3.7-sonnet
	const legacyMatch = name.match(
		/^(claude)-(\d+)-(\d+)-(sonnet|opus|haiku)(.*)$/,
	)
	if (legacyMatch) {
		const [, prefix, major, minor, variant, rest] = legacyMatch
		name = `${prefix}-${major}.${minor}-${variant}${rest}`
	}

	// 4. Convert trailing version dashes to dots: claude-sonnet-4-5 → claude-sonnet-4.5
	name = name.replace(
		/^(claude-(?:sonnet|opus|haiku)-)(\d+)-(\d+)(-.+)?$/,
		(_, prefix, major, minor, rest) =>
			`${prefix}${major}.${minor}${rest ?? ""}`,
	)

	// 5. Strip date suffix: claude-sonnet-4.5-20250929 → claude-sonnet-4.5
	name = name.replace(/-\d{8}$/, "")

	return name
}

export interface ModelResolution {
	internalId: string
	source: "alias" | "cache" | "hidden" | "passthrough"
	isVerified: boolean
}

export class ModelResolver {
	private cachedModels: Set<string> = new Set()

	setCachedModels(models: string[]) {
		this.cachedModels = new Set(models.map(normalizeModelName))
	}

	resolve(requestedModel: string): ModelResolution {
		// 1. Check aliases
		const aliased = MODEL_ALIASES[requestedModel]
		if (aliased) {
			return { internalId: aliased, source: "alias", isVerified: true }
		}

		// 2. Normalize
		const normalized = normalizeModelName(requestedModel)

		// 3. Check alias again after normalization
		const aliased2 = MODEL_ALIASES[normalized]
		if (aliased2) {
			return { internalId: aliased2, source: "alias", isVerified: true }
		}

		// 4. Check dynamic cache
		if (this.cachedModels.has(normalized)) {
			return { internalId: normalized, source: "cache", isVerified: true }
		}

		// 5. Check hidden models
		if (normalized in HIDDEN_MODELS) {
			return {
				internalId: normalized,
				source: "hidden",
				isVerified: true,
			}
		}

		// 6. Pass-through (gateway philosophy: let Kiro decide)
		return {
			internalId: normalized,
			source: "passthrough",
			isVerified: false,
		}
	}

	getAvailableModels(): string[] {
		return [...this.cachedModels, ...Object.keys(HIDDEN_MODELS)]
	}
}
