import { Route, Routes } from 'react-router'

import { AppLayout } from '@/components/app-layout'
import { ApiStatus } from '@/features/health/api-status'

/**
 * Routing shell. Feature routes are added as they land:
 *   /standings/drivers, /standings/constructors, /schedule, /results,
 *   /races/:season/:round
 */
export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<ApiStatus />} />
      </Routes>
    </AppLayout>
  )
}
