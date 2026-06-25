import type { Metadata } from "next"
import { Inter, JetBrains_Mono } from "next/font/google"
import "./globals.css"
import { Sidebar } from "@/components/sidebar"

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
}

export default function RootLayout({
	children,
}: Readonly<{
	children: React.ReactNode
}>) {
	return (
		<html
			lang="en"
			className={`${inter.variable} ${jetbrainsMono.variable} h-full antialiased dark`}
		>
			<body className="min-h-full flex bg-background text-foreground font-sans">
				<Sidebar />
				<main className="flex-1 ml-56 p-6">{children}</main>
			</body>
		</html>
	)
}
