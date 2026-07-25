import type { ReactNode } from 'react'

import { ThemeToggle } from '@/components/theme-toggle'

/**
 * Application shell. Landmarks (header/nav/main) and the skip link are here so
 * every page inherits a correct document structure rather than each view
 * reinventing one.
 */
export function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-dvh bg-background text-foreground">
      {/* First focusable element: lets keyboard users bypass the header. */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:ring-2 focus:ring-ring"
      >
        Skip to main content
      </a>

      <header className="border-b border-border">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-4">
          <span className="text-base font-semibold tracking-tight">F1 Dashboard</span>
          <ThemeToggle />
        </div>
      </header>

      <main id="main" className="mx-auto max-w-5xl px-4 py-8">
        {children}
      </main>
    </div>
  )
}
