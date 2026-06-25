export enum AuthType {
	KIRO_DESKTOP = "kiro_desktop",
	AWS_SSO_OIDC = "aws_sso_oidc",
}

export interface KiroCredentials {
	accessToken: string
	refreshToken: string
	expiresAt: string
	region?: string
	profileArn?: string
	clientId?: string
	clientSecret?: string
	clientIdHash?: string
}

export interface AccountCredentialConfig {
	type: "json" | "sqlite" | "refresh_token"
	path?: string
	refresh_token?: string
	profile_arn?: string
	region?: string
	api_region?: string
}
