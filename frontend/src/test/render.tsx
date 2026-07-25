import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'

import { createStore } from '@/store'
import { ThemeProvider } from '@/theme/theme-provider'

/**
 * Renders with the providers the app actually uses, and a fresh store per test
 * so RTK Query's cache cannot leak results between tests.
 */
export function renderWithProviders(ui: ReactElement, { route = '/' } = {}) {
  const store = createStore()
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider>
          <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
        </ThemeProvider>
      </Provider>,
    ),
  }
}
