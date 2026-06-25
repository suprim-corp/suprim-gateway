import type { NextConfig } from "next"

const nextConfig: NextConfig = {
	rewrites: async () => [
		{
			source: "/api/:path*",
			destination: "http://localhost:3001/:path*",
		},
		{
			source: "/v1/:path*",
			destination: "http://localhost:3001/v1/:path*",
		},
	],
}

export default nextConfig
