CREATE TABLE `accounts` (
	`id` text PRIMARY KEY NOT NULL,
	`type` text NOT NULL,
	`path` text,
	`region` text DEFAULT 'us-east-1' NOT NULL,
	`api_region` text,
	`enabled` integer DEFAULT true NOT NULL,
	`status` text DEFAULT 'unknown' NOT NULL,
	`last_used_at` integer,
	`failure_count` integer DEFAULT 0 NOT NULL,
	`last_failure_at` integer,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `request_logs` (
	`id` text PRIMARY KEY NOT NULL,
	`virtual_key_id` text,
	`account_id` text,
	`model` text NOT NULL,
	`requested_model` text,
	`status` integer NOT NULL,
	`prompt_tokens` integer,
	`completion_tokens` integer,
	`total_tokens` integer,
	`latency_ms` integer,
	`first_token_ms` integer,
	`streaming` integer,
	`client_ip` text,
	`error_message` text,
	`created_at` integer NOT NULL,
	FOREIGN KEY (`virtual_key_id`) REFERENCES `virtual_keys`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`account_id`) REFERENCES `accounts`(`id`) ON UPDATE no action ON DELETE no action
);
--> statement-breakpoint
CREATE TABLE `virtual_keys` (
	`id` text PRIMARY KEY NOT NULL,
	`name` text NOT NULL,
	`key_hash` text NOT NULL,
	`key_prefix` text NOT NULL,
	`account_id` text,
	`enabled` integer DEFAULT true NOT NULL,
	`revoked_at` integer,
	`rate_limit_per_min` integer DEFAULT 60 NOT NULL,
	`allowed_models` text,
	`budget_period` text,
	`budget_tokens` integer,
	`budget_requests` integer,
	`period_tokens_used` integer DEFAULT 0 NOT NULL,
	`period_requests_used` integer DEFAULT 0 NOT NULL,
	`period_reset_at` integer,
	`total_requests` integer DEFAULT 0 NOT NULL,
	`total_tokens` integer DEFAULT 0 NOT NULL,
	`last_used_at` integer,
	`created_at` integer NOT NULL,
	FOREIGN KEY (`account_id`) REFERENCES `accounts`(`id`) ON UPDATE no action ON DELETE no action
);
--> statement-breakpoint
CREATE UNIQUE INDEX `virtual_keys_key_hash_unique` ON `virtual_keys` (`key_hash`);