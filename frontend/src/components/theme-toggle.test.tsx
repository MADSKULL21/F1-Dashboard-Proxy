import userEvent from '@testing-library/user-event'
import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import { ThemeToggle } from '@/components/theme-toggle'
import { renderWithProviders } from '@/test/render'
import { THEME_STORAGE_KEY } from '@/theme/theme-context'

describe('ThemeToggle', () => {
  beforeEach(() => {
    window.localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('is reachable and operable by keyboard alone', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ThemeToggle />)

    await user.tab()
    expect(screen.getByRole('button', { name: /change theme/i })).toHaveFocus()

    await user.keyboard('{Enter}')
    expect(await screen.findByRole('menu')).toBeInTheDocument()
  })

  it('applies the dark class when dark is chosen', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ThemeToggle />)

    await user.click(screen.getByRole('button', { name: /change theme/i }))
    await user.click(await screen.findByRole('menuitem', { name: 'Dark' }))

    expect(document.documentElement).toHaveClass('dark')
  })

  it('persists the choice so a reload keeps it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ThemeToggle />)

    await user.click(screen.getByRole('button', { name: /change theme/i }))
    await user.click(await screen.findByRole('menuitem', { name: 'Dark' }))

    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
  })

  it('removes the dark class when light is chosen', async () => {
    const user = userEvent.setup()
    document.documentElement.classList.add('dark')
    renderWithProviders(<ThemeToggle />)

    await user.click(screen.getByRole('button', { name: /change theme/i }))
    await user.click(await screen.findByRole('menuitem', { name: 'Light' }))

    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('exposes the current preference in the trigger label', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    renderWithProviders(<ThemeToggle />)

    expect(screen.getByRole('button', { name: /theme: dark/i })).toBeInTheDocument()
  })
})
