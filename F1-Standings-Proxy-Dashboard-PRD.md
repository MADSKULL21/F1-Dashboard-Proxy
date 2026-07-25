# Product Requirements Document
## F1 Standings Proxy Dashboard

**Version:** 1.0 (MVP)
**Status:** Draft for implementation
**Author:** Product Discovery Session

---

## 1. Executive Summary

The F1 Standings Proxy Dashboard is a full-stack web application that displays Formula 1 driver standings, constructor standings, upcoming race schedule, and race results. Its defining architectural principle is that the frontend never communicates with any external Formula 1 data source directly — all external data flows through a Spring Boot backend that owns caching, retries, and failure handling.

The project serves two purposes simultaneously: it is a personal learning vehicle for production-grade API-proxy architecture, caching strategy, resilience patterns, and testing discipline — and it is intended to be used by real F1 fans, not just kept as a portfolio piece. It is being built solo, at a casual pace, with a real path to a V2 that adds historical data, near-live race data, authentication, and social/predictive features.

---

## 2. Product Vision

### 2.1 Problem Statement
F1 fans who want a clean, fast, ad-light view of current standings and the race calendar don't have a lightweight purpose-built option — most existing options are official apps bundled with unrelated features, or fan sites cluttered with ads and news. This project builds a focused, fast alternative, while doubling as a demonstration of secure third-party API integration patterns (proxying, caching, resilience) that the author wants to master.

### 2.2 Target Users
- **Primary:** General F1 fans who want a quick, clean standings/schedule view.
- **Secondary:** The author, as a portfolio/learning artifact demonstrating backend architecture skill.

### 2.3 Goals
- Ship a working, publicly usable product — not just a local demo.
- Demonstrate mastery of: secure API proxying, caching strategy, resilience/retry patterns, clean layered architecture, and testing discipline.
- Build a foundation that can scale into V2 without major rework (hence Postgres and Redis are introduced in MVP, and the DB schema is designed with OAuth in mind).

### 2.4 Success Metrics
- End-to-end system works reliably under real (if modest) production traffic.
- Every architectural decision is defensible and explainable (this is as much a learning-outcome metric as a product metric).
- Clean test coverage on the backend (unit + integration).
- The system degrades gracefully rather than breaking when the upstream API is slow, rate-limited, or down.

### 2.5 Scope (MVP)
- Current-season driver standings
- Current-season constructor standings
- Upcoming race schedule (future races only)
- Recent Results (separate view for completed races)
- Race Detail page (circuit info + full results for completed races; schedule info for upcoming races)
- Driver mini-profile (click-through from standings)
- Dark mode
- Redis caching + Postgres persistence of a standings/results snapshot (audit trail / cache-of-last-resort)

### 2.6 Out of Scope (Deferred to V2)
- Historical (past-season) standings and results
- Near-live/in-race data (session positions, intervals) via OpenF1
- User authentication (OAuth via Google/GitHub) and favourite drivers/teams
- Driver comparison tool, race predictions, fantasy-style features

---

## 3. User Personas

**"Casual Chris"** — Watches most races, wants to glance at the standings after a race weekend without digging through a cluttered official app. Cares about speed and clarity, not depth.

**"Stats Sam"** — Wants full race results (positions, points, gaps) after every race, checks the schedule regularly to plan his weekend around sessions. Will be the first to want V2 features like historical comparisons.

---

## 4. Functional Requirements

### 4.1 Driver Standings
- Displays: position, driver name, nationality flag, constructor, points, wins, podiums, DNFs.
- Scoped to current season only (historical seasons are V2).
- Clicking a driver opens a mini-profile/detail view.
- **Off-season behavior:** if no current-season data exists yet, show an explicit "season hasn't started" empty state (with next season's start date if determinable from the schedule data) — never a blank or broken-looking table.

### 4.2 Constructor Standings
- Displays: position, team, points, wins (podiums/DNF fields as applicable at team level).
- Same off-season empty-state behavior as Driver Standings.

### 4.3 Race Schedule ("Upcoming")
- Lists **only future races** in the current season.
- Each entry: round number, circuit name, country, race date, session times (practice/qualifying/race) if available from the API.
- All times displayed in the **user's local browser timezone** (converted from the API's UTC/circuit-local values).

### 4.4 Recent Results
- Separate view/tab (not merged into the Schedule) listing completed races in the current season.
- Each entry links to the Race Detail page for that race.

### 4.5 Race Detail
- **Upcoming race:** circuit info, date/time (local), session schedule.
- **Completed race:** circuit info + full results (final positions, points awarded, gap/time to leader per driver).

### 4.6 Driver Mini-Profile
- Triggered from standings tables.
- Shows basic driver info (name, nationality, constructor, code/number) plus season stats already available from standings data. No new API surface required beyond what standings/results already provide.

### 4.7 Business Rules
- **Current season detection:** the backend must determine the "current" F1 season correctly across season boundaries — F1 seasons don't cleanly align to Jan 1 (pre-season starts, season finale in ~Dec). The backend should treat "current season" as the most recent season with any race data, adjusted around the actual calendar rather than assuming calendar-year = season-year naively.
- **Upstream failure handling:** on a request to Jolpica, retry with exponential backoff; if retries are exhausted, serve the last cached response from Redis (or the Postgres snapshot if Redis is cold) with a visible **"Last updated: HH:MM"** notice in the UI. Never show a hard error if any cached data — however stale — exists.
- **No cross-provider fallback for MVP:** OpenF1 is explicitly not used as a fallback for standings/schedule/results in MVP, because its data model doesn't cover championship standings. This is documented so a future engineer doesn't "fix" it by wiring in a fallback that silently returns incomplete data.

### 4.8 User Stories
- *As a fan, I want to see who's leading the championship so I can follow the season at a glance.*
- *As a fan, I want to check the upcoming race schedule in my own timezone so I don't miss a session.*
- *As a fan, I want to see full results from the last race so I know what happened even if I missed it live.*

---

## 5. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Performance | Prioritize perceived performance (skeleton loaders, optimistic UI) over raw latency numbers for MVP. |
| Scalability | Design for medium scale — thousands of users, with traffic spikes around race weekends. |
| Security | Full posture from MVP despite no user auth yet: HTTPS everywhere, rate-limiting on public endpoints, CORS locked to the known frontend origin, standard security headers (CSP, HSTS, X-Content-Type-Options, etc.), automated dependency vulnerability scanning in CI. |
| Availability | Basic uptime is acceptable — side-project SLA, not production SLA. Graceful degradation (cache fallback) matters more than uptime chasing. |
| Reliability | Retry + cache-fallback pattern (see 4.7) is the core reliability mechanism. |
| Accessibility | Full WCAG 2.1 AA compliance from the start — semantic HTML, keyboard navigation, sufficient color contrast (including dark mode), screen-reader-friendly tables and forms. |
| Browser Support | Modern evergreen browsers only: latest Chrome, Firefox, Edge, Safari. No legacy/IE support. |
| Responsiveness | Fully responsive layout; standings tables must degrade sensibly on mobile widths. |

---

## 6. Technical Architecture

### 6.1 High-Level Flow
```
React (TS) Frontend  →  Spring Boot Backend  →  Jolpica-F1 API
                              │
                    ┌─────────┴─────────┐
                    │                   │
                 Redis Cache      Postgres (snapshot/audit)
```

The frontend **never** calls Jolpica directly. All external data access is mediated by the backend.

### 6.2 Frontend
- **Framework:** React + TypeScript
- **State management:** Redux Toolkit
- **Styling:** responsive, dark-mode-first design system (implementation detail for the dev to choose a CSS approach — e.g. CSS Modules or a utility framework — consistent with accessibility requirements)
- **Loading/empty/error states:** skeleton loaders for in-flight requests; explicit empty state for off-season; visible "last updated" banner when serving stale/cached data
- **Deployment:** Vercel or Netlify (free tier)

### 6.3 Backend
- **Framework:** Spring Boot
- **Architecture:** layered — Controller → Service → Repository/Client → DTOs — for testability and separation of concerns.
- **API style:** REST, clean resource-based URLs (e.g. `/api/standings/drivers`, `/api/standings/constructors`, `/api/schedule/upcoming`, `/api/results/recent`, `/api/races/{season}/{round}`).
- **Error handling:** global exception handling via `@ControllerAdvice`, returning a consistent JSON error shape: `{ code, message, timestamp, path }`.
- **Caching:** Redis (hosted on Upstash free tier), short TTL (1–5 minutes) to stay close to real-time while protecting Jolpica from excessive calls.
- **Persistence:** Postgres, storing a periodic snapshot of standings/results data — acts as a second-tier fallback beneath Redis and as an audit trail. Schema designed with V2 `users`/`favourites`/OAuth tables in mind, even though those tables are unused in MVP.
- **Resilience:** retry-with-backoff against Jolpica; Redis cache on success; Postgres snapshot as last-resort fallback if Redis is also unavailable.
- **Deployment:** Render free tier (JVM heap tuned to fit the 512MB memory ceiling; cold starts after inactivity are an accepted tradeoff of the $0 hosting model).

### 6.4 Data Source
- **Jolpica-F1** (`https://api.jolpi.ca/ergast/f1/`) — free, open-source, Ergast-compatible successor API. Used for standings, schedule, and race results in MVP.
- **OpenF1** — explicitly deferred to V2, for near-live session data (2023+ coverage only; no standings data).

### 6.5 CI/CD & Observability
- **CI/CD:** GitHub Actions — lint, test, build, deploy on push to `main`.
- **Logging:** structured logging in the backend (e.g. JSON-formatted logs).
- **Monitoring:** a free-tier tool (Grafana Cloud free tier or Sentry) for error tracking and basic metrics visibility.

---

## 7. Database Design (Postgres)

MVP tables (minimum viable schema):

- **`season_snapshot`** — periodic cached copy of standings/results data (season, round, payload JSON or normalized columns, `fetched_at` timestamp). This is the fallback-of-last-resort and audit trail described in 4.7 / 6.3.
- **`races`** (optional normalized alternative/complement to the snapshot) — season, round, circuit, date, status (upcoming/completed).

V2-ready tables (created later, not populated in MVP):
- **`users`** — OAuth identity (provider, provider_user_id, email, display_name, created_at). No password field, since auth is OAuth-only (Google/GitHub).
- **`favourites`** — user_id, entity_type (driver/constructor), entity_id.

**Indexing:** index `season_snapshot` on `(season, round)`; index `races` on `(season, status)` for fast "upcoming" filtering.

---

## 8. API Specification (MVP endpoints)

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/standings/drivers` | Current-season driver standings |
| GET | `/api/standings/constructors` | Current-season constructor standings |
| GET | `/api/schedule/upcoming` | Future races in current season |
| GET | `/api/results/recent` | Completed races in current season |
| GET | `/api/races/{season}/{round}` | Race detail (results if completed, schedule if upcoming) |
| GET | `/api/drivers/{driverId}` | Driver mini-profile |

All responses follow a consistent envelope and the global error format described in 6.3.

---

## 9. Error Handling Strategy

- All backend exceptions are caught centrally via `@ControllerAdvice` and mapped to a consistent JSON shape.
- Upstream (Jolpica) failures trigger retry-with-backoff, then cache fallback (Redis → Postgres snapshot), never a raw 5xx surfaced to the user if any cached data exists.
- Frontend distinguishes three states per view: loading (skeleton), error (only when truly no data — including cache — is available), and stale-but-available (data shown with a "last updated" notice).

---

## 10. Caching Strategy

- **Redis:** primary cache, 1–5 minute TTL, keyed by endpoint + season/round parameters.
- **Postgres snapshot:** secondary, longer-lived fallback populated on a schedule (or on successful Redis refresh), used only when both the live Jolpica call and Redis are unavailable.

---

## 11. Testing Strategy

- **Backend:** unit tests (service layer, retry/cache logic) and integration tests (controller + repository, against a test database) are the priority for MVP — this is where the resilience/testing learning goal is most exercised.
- **Frontend:** lighter-weight for MVP — component-level tests for critical paths (standings table rendering, error/empty states) rather than full E2E coverage.
- V2 can expand into E2E testing (Playwright/Cypress) once the feature surface grows.

---

## 12. Deployment Architecture

- **Frontend:** Vercel/Netlify free tier (static hosting, CDN).
- **Backend:** Render free tier (containerized Spring Boot, heap-tuned for 512MB).
- **Database:** Render free Postgres.
- **Cache:** Upstash free Redis-compatible store.
- **CI/CD:** GitHub Actions pipeline — lint → test → build → deploy on push to `main`.
- **Known tradeoff:** free-tier backend cold starts (30–50s) after inactivity; mitigated by cache-first reads once warm, and acceptable given the "basic uptime is fine" NFR.

---

## 13. Risks & Assumptions

**Risks**
- Render free-tier memory limits (512MB) may require aggressive JVM tuning or trimming dependencies to avoid OOM under load.
- Jolpica is a volunteer-maintained project with modest hosting budget — it could itself experience downtime or rate-limiting, which is exactly why the retry/cache/snapshot resilience chain exists.
- Free-tier cold starts could hurt first-impression performance for new users landing during a quiet period.

**Assumptions**
- Jolpica's Ergast-compatible schema remains stable enough not to require frequent client-side remapping.
- "Thousands of users with race-weekend spikes" is an aspirational target the free-tier architecture should gracefully degrade under, not guarantee flawless performance at.
- OAuth-only auth (no password storage) is acceptable for the target audience in V2.

---

## 14. Development Roadmap

**MVP (V1)**
- Driver/Constructor standings, upcoming schedule, recent results, race detail, driver mini-profile, dark mode.
- Full resilience chain (retry → Redis → Postgres snapshot).
- Security hardening, WCAG 2.1 AA accessibility, CI/CD, basic observability.

**V2**
- Historical season data (standings, schedule, results for past seasons via Jolpica).
- Near-live race data via OpenF1 (session positions, intervals) — with a UI treatment that makes clear this is polled-near-live, not sub-second telemetry.
- OAuth authentication (Google, GitHub) and favourite drivers/teams (Postgres `users`/`favourites` tables activated).

**V3 / Stretch**
- Driver comparison tool.
- Race predictions.
- Fantasy-style features.

---

## 15. Future Enhancements (beyond current roadmap visibility)
- Push notifications for race weekends (would require a notifications service).
- Public API rate-limited access for other developers (if the project gains traction).
- Analytics on which views/drivers/teams are most viewed (would need to be weighed against the accessibility/privacy-light MVP posture).
