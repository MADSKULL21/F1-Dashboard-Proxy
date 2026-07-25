import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'

/**
 * Provenance the backend attaches to every payload (PRD 8). `stale` and
 * `fetchedAt` drive the "Last updated" banner required by PRD 4.7, so the UI
 * never has to infer freshness.
 *
 * Unused until F1 introduces the first real endpoint; declared here because the
 * envelope is the contract every feature inherits.
 */
export type ResponseMeta = {
  season: number | null
  round: number | null
  fetchedAt: string
  source: 'LIVE' | 'CACHE' | 'SNAPSHOT'
  stale: boolean
}

export type ApiResponse<T> = {
  data: T
  meta: ResponseMeta
}

/** /api/health deliberately sits outside the envelope: it has no F1 data. */
export type HealthStatus = {
  status: string
  version: string
  timestamp: string
}

const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'

export const f1Api = createApi({
  reducerPath: 'f1Api',
  baseQuery: fetchBaseQuery({ baseUrl }),
  // Populated as features land: 'DriverStandings', 'Schedule', 'Results'.
  tagTypes: ['Health'],
  endpoints: (build) => ({
    health: build.query<HealthStatus, void>({
      query: () => '/health',
      providesTags: ['Health'],
    }),
  }),
})

export const { useHealthQuery } = f1Api
