import { env } from "../config"

const LEVELS = { debug: 0, info: 1, warn: 2, error: 3 } as const
const currentLevel = LEVELS[env.LOG_LEVEL]

const c = {
	reset: "\x1b[0m",
	dim: "\x1b[2m",
	cyan: "\x1b[36m",
	green: "\x1b[32m",
	yellow: "\x1b[33m",
	red: "\x1b[31m",
	magenta: "\x1b[35m",
}

function ts(): string {
	return `${c.dim}${new Date().toISOString()}${c.reset}`
}

export const logger = {
	debug(msg: string, ...args: unknown[]) {
		if (currentLevel <= LEVELS.debug)
			console.debug(`${ts()} ${c.magenta}DEBUG${c.reset} ${msg}`, ...args)
	},
	info(msg: string, ...args: unknown[]) {
		if (currentLevel <= LEVELS.info)
			console.log(`${ts()} ${c.green}INFO${c.reset}  ${msg}`, ...args)
	},
	warn(msg: string, ...args: unknown[]) {
		if (currentLevel <= LEVELS.warn)
			console.warn(`${ts()} ${c.yellow}WARN${c.reset}  ${msg}`, ...args)
	},
	error(msg: string, ...args: unknown[]) {
		if (currentLevel <= LEVELS.error)
			console.error(`${ts()} ${c.red}ERROR${c.reset} ${msg}`, ...args)
	},
}
