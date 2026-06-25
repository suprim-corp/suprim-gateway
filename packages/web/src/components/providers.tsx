"use client"

import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { ThemeProvider } from "next-themes"
import type React from "react"
import { useState } from "react"

export function Providers({ children }: { children: React.ReactNode }) {
	const [queryClient] = useState(
		() =>
			new QueryClient({
				defaultOptions: {
					queries: {
						staleTime: 5000,
						refetchOnWindowFocus: false,
					},
				},
			}),
	)

	return (
		<QueryClientProvider client={queryClient}>
			<ThemeProvider attribute="class" defaultTheme="system" enableSystem enableColorScheme={false} disableTransitionOnChange>
				{children}
			</ThemeProvider>
		</QueryClientProvider>
	)
}
