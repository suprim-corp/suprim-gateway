import type { Metadata } from "next"
import { Inter, JetBrains_Mono } from "next/font/google"
import "./globals.css"
import type React from "react"
import { Providers } from "@/components/providers"

const inter = Inter({
	variable: "--font-inter",
	subsets: ["latin"],
})

const jetbrainsMono = JetBrains_Mono({
	variable: "--font-jetbrains-mono",
	subsets: ["latin"],
})

export const metadata: Metadata = {
	title: "Kiro Gateway",
	description: "Kiro API Gateway Dashboard",
	icons: {
		icon: "https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/kiro-color.png",
	},
}

export default function RootLayout({
	children,
}: Readonly<{
	children: React.ReactNode
}>) {
	return (
		<html
			lang="en"
			className={`${inter.variable} ${jetbrainsMono.variable} h-full antialiased`}
		>
			<body className="min-h-full flex bg-background text-foreground font-sans">
				<Providers>{children}</Providers>
			</body>
		</html>
	)
}
