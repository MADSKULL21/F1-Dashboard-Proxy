import { configureStore } from '@reduxjs/toolkit'
import { setupListeners } from '@reduxjs/toolkit/query'

import { f1Api } from './api'

export function createStore() {
  const store = configureStore({
    reducer: {
      [f1Api.reducerPath]: f1Api.reducer,
    },
    middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(f1Api.middleware),
  })

  // Refetch when the tab regains focus or the network reconnects — useful during
  // a race weekend, and it costs nothing thanks to the backend cache.
  setupListeners(store.dispatch)
  return store
}

export const store = createStore()

export type AppStore = ReturnType<typeof createStore>
export type RootState = ReturnType<AppStore['getState']>
export type AppDispatch = AppStore['dispatch']
