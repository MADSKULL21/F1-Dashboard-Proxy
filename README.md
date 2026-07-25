# F1 Standings Proxy Dashboard

Formula 1 driver and constructor standings, race schedule and results — with a
Spring Boot backend that owns all external data access, caching, retries and
failure handling. The frontend never talks to a Formula 1 API directly.

See [`F1-Standings-Proxy-Dashboard-PRD.md`](F1-Standings-Proxy-Dashboard-PRD.md)
for requirements and [`docs/decisions.md`](docs/decisions.md) for why the stack
looks the way it does.

## Stack

| | |
|---|---|
| Backend | Spring Boot 4.1.0, Java 25, Maven |
| Resilience | Resilience4j (retry + circuit breaker) |
| Cache | Redis (Upstash) |
| Database | Postgres (Neon), Flyway migrations |
| Frontend | React 19, Vite 8, TypeScript, Redux Toolkit + RTK Query |
| UI | Tailwind 4, shadcn/ui (Radix primitives) |
| Upstream | [Jolpica-F1](https://api.jolpi.ca/ergast/f1/) (Ergast-compatible) |

## Layout

```
backend/     Spring Boot API
frontend/    React SPA
docs/        Decision log
docker-compose.yml   Local Postgres + Redis (used from F2 onwards)
```

## Running locally

Requires JDK 25, Node 24+ and Docker.

```bash
# Backend — http://localhost:8080
cd backend
./mvnw spring-boot:run

# Frontend — http://localhost:5173
cd frontend
npm install
npm run dev
```

The backend has no external dependencies yet. From F2 (resilience chain) onwards,
start Postgres and Redis first:

```bash
docker compose up -d
```

### Verifying

```bash
cd backend  && ./mvnw verify        # unit + integration tests
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
```

## Deployment

`main` auto-deploys. Render and Vercel both build from the repository on push, so
there is no deploy step in CI — GitHub Actions only gates correctness.

| Piece | Where | Notes |
|---|---|---|
| Frontend | Vercel | Root directory `frontend` |
| Backend | Render | Docker, root directory `backend`, 512MB |
| Database | Neon | Free tier; **not** Render Postgres, which expires after 30 days |
| Cache | Upstash | Redis-compatible |

### One-time manual setup

These require accounts and cannot be scripted:

1. **Neon** — create a project, copy the pooled connection string.
2. **Upstash** — create a Redis database, copy the connection details.
3. **Render** — new Web Service from this repo, root directory `backend`,
   runtime Docker. Set env vars:
   - `APP_CORS_ALLOWED_ORIGINS` — the exact Vercel URL (no trailing slash)
   - `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
     `SPRING_DATASOURCE_PASSWORD` (from Neon)
   - `SPRING_DATA_REDIS_URL` (from Upstash)
4. **Vercel** — new project from this repo, root directory `frontend`. Set
   `VITE_API_BASE_URL` to the Render URL **including** `/api`.
5. Tighten `connect-src` in [`frontend/vercel.json`](frontend/vercel.json) from
   `https://*.onrender.com` to the exact Render host once it is known.

Cold starts are expected: Render free web services sleep after 15 minutes idle
and take ~1 minute to wake. A CDS archive in the image cuts JVM startup to ~1.8s
of that.

## Workflow

One feature per branch, vertical slice, merged via PR with green CI:

```bash
git switch -c feature/<name>
# backend: tests first. frontend: tests after, targeted.
git push -u origin feature/<name>
gh pr create --fill
gh pr merge --squash --delete-branch    # only when CI is green
```

Every feature's definition of done includes: semantic markup, keyboard-only
operation, AA contrast verified in **both** themes, and an entry in
`docs/decisions.md`.

## Roadmap

Scaffold → driver standings → resilience chain → observability → rate limiting →
constructor standings → upcoming schedule → recent results (+ podiums/DNFs) →
race detail → driver mini-profile.
