import { createContext } from 'react'

/** What the user asked for. 'system' defers to the OS setting. */
export type ThemePreference = 'light' | 'dark' | 'system'

/** What is actually on screen once 'system' has been resolved. */
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'f1-theme'

export type ThemeContextValue = {
  preference: ThemePreference
  resolved: ResolvedTheme
  setPreference: (preference: ThemePreference) => void
}

export const ThemeContext = createContext<ThemeContextValue | null>(null)
