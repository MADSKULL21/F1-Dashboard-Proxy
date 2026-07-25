# Decision log

Append-only. Every entry records the alternatives that were rejected and why —
PRD 2.3 makes "every architectural decision is defensible and explainable" a
success metric, and a decision without its rejected alternatives is not
explainable.

Newest entries at the bottom of each session block.

---

## 2026-07-25 — Planning session (before any code)

### Monorepo, not two repositories

`backend/` and `frontend/` in one repo.

- **Rejected:** separate `f1-dashboard-api` / `f1-dashboard-web` repos.
- **Why:** features are built as vertical slices, so one slice touches both
  halves. Two repos would mean two branches and two PRs per feature, with
  contract changes coordinated across them, roughly eight times over.
- **Cost accepted:** CI needs path filters so a CSS change does not run Maven,
  and Vercel needs a root-directory setting.

### Vertical feature slices, not horizontal layers

Each branch delivers one user-visible capability from Jolpica through to the UI.

- **Rejected:** all-backend-then-all-frontend; also a throwaway walking skeleton.
- **Why:** every merge to `main` leaves a demoable, deployable product, and
  "test it" means something you can look at. A wrong API shape surfaces at the
  first consumer rather than after six endpoints are built on it.
- **Cost accepted:** the first branch is the heaviest because it drags in
  scaffolding — which is why scaffolding was split out into its own branch.

### Spring Boot 4.1.0 on Java 25

- **Rejected:** Boot 3.5.x on Java 21, which is what essentially every current
  tutorial and StackOverflow answer targets.
- **Why:** currency. Java 25 is an LTS and already installed; starting a
  multi-month project one major version behind means beginning V2 with a
  migration.
- **Cost accepted:** Boot 4 moved to Jackson 3 (`tools.jackson`), renamed
  `spring-boot-starter-web` to `spring-boot-starter-webmvc`, split test starters
  per-module, and relocated `@WebMvcTest` / `@AutoConfigureMockMvc` to
  `org.springframework.boot.webmvc.test.autoconfigure`. Guides written for 3.x
  will not match. Prefer current docs over recalled knowledge.

### Resilience4j rather than Spring Framework 7's built-in `@Retryable`

- **Rejected:** the built-in `@Retryable` (zero dependencies); also a
  hand-rolled backoff loop.
- **Why:** Resilience4j adds a circuit breaker and Micrometer metrics. The
  breaker matters concretely: PRD 13 notes Jolpica is volunteer-run on a modest
  budget, and blind retries from every request during an outage make us part of
  its problem. Metrics feed the PRD 6.5 observability requirement.
- **Cost accepted:** one dependency and a config block.

### Normalised Postgres tables, not a JSONB payload

`races`, `driver_standings`, `results` as real columns.

- **Rejected:** a single `season_snapshot` table holding the serialised DTO as
  JSONB.
- **Why:** V2 wants historical queries, and this is the schema that supports
  them without a later migration.
- **Risk introduced:** the snapshot-restore path now rebuilds the DTO from rows,
  so there are two independent mapping paths (Jolpica→DTO and rows→DTO) that can
  drift apart silently. **Mitigation: F2 must include a contract test asserting
  both paths produce an equivalent DTO from the same fixture.**

### Flyway with versioned SQL per feature branch

- **Rejected:** Liquibase (portability we never need); `ddl-auto=update`
  (unreviewable, unsafe on a live database, drifts silently).
- **Why:** identical SQL runs on the laptop, in Testcontainers and on Neon, so
  the schema the tests prove is the schema production gets. One migration per
  branch doubles as a changelog. `ddl-auto=validate` stops entities and
  migrations disagreeing.

### Snapshot written through on every successful fetch, not on a schedule

- **Rejected:** an `@Scheduled` refresh job.
- **Why:** Render free web services sleep after 15 minutes idle, so a scheduled
  job does not run during exactly the quiet periods it exists to cover. It would
  also generate upstream calls with no users, which is unkind to Jolpica.
- **Constraint:** the snapshot write must be asynchronous and must never fail the
  user's request — log and count failures instead.

### Neon for Postgres, not Render Postgres

- **Rejected:** Render Postgres as specified in PRD 12.
- **Why:** Render's free Postgres **expires 30 days after creation** and is
  deleted 14 days later, with 1GB storage and no backups. At a casual pace the
  database would die mid-project and take the fallback tier with it — precisely
  the failure the resilience chain exists to prevent. Neon's free tier does not
  expire and is plain Postgres, so no code or migration changes.
- **Rejected also:** Supabase — free projects pause after ~1 week idle and need a
  manual unpause, a worse failure mode than Neon's automatic cold start.
- **PRD 12 amended accordingly.**

### `{ data, meta }` body envelope, not HTTP headers

`meta` carries `season`, `round`, `fetchedAt`, `source` (LIVE/CACHE/SNAPSHOT) and
`stale`.

- **Rejected:** bare payloads with `X-Data-Source` / `Age` headers.
- **Why:** the "Last updated" banner in PRD 4.7 becomes a pure function of
  `meta`, with no per-endpoint `transformResponse` plumbing in RTK Query, and
  headers are easy to lose through a proxy or forget on a fallback path.

### Trust Jolpica's `/current`, but read the resolved season back out

- **Rejected:** resolving the season ourselves from `/seasons`; hardcoding it in
  config.
- **Why:** upstream owns the calendar, so there is no home-grown date arithmetic
  to get wrong at a season boundary. Reading `StandingsTable.season` out of the
  response means cache keys, snapshot rows and API responses all carry an
  explicit year rather than depending on the ambiguous word "current".
- **Verified against the live API:** off-season returns HTTP 200 with
  `total: "0"`, `StandingsLists: []` and `round: null`, so the empty state is
  detectable without error handling. **Unproven assumption:** that January 2027
  behaves the same way as a query for 2027 does today. Re-check then.

### Podiums and DNFs deferred to the results feature

- **Rejected:** deriving them in the standings feature; cutting them entirely.
- **Why:** Jolpica's standings response carries only `points` and `wins`
  (verified). Podiums and DNFs require paginating all season results (220 rows
  at 100/page = 3 calls) and classifying `status` strings. That pipeline belongs
  to the results feature, which needs it anyway — building it in standings first
  would mean writing it twice.
- **PRD 4.1 amended accordingly.**

### Backend maps nationality demonym to ISO code; frontend renders SVG flags

- **Rejected:** a frontend lookup table; emoji flags.
- **Why:** Jolpica returns demonyms ("Italian", "British") with no country code,
  and demonym→ISO is data normalisation, so it belongs in the layer with the
  stricter test discipline. Emoji flags render as two boxed letters on Windows,
  which PRD 5 commits to supporting via Edge.
- **Accessibility:** the flag is decorative; the nationality text carries the
  meaning.

### Security split by dependency

CORS, security headers and dependency scanning in the scaffold; rate limiting in
its own branch once Redis exists.

- **Rejected:** one security branch after the features (CORS cannot actually be
  deferred — the scaffold's own definition of done requires a cross-origin fetch
  to succeed); everything in the scaffold (rate limiting would drag Redis in, or
  ship an in-memory limiter that is wrong the moment Render restarts).

### Dark mode and accessibility are cross-cutting, not branches

Theme system in the scaffold; WCAG AA in every feature's definition of done.

- **Rejected:** an accessibility audit branch at the end.
- **Why:** retrofitting accessibility is far more expensive than building it in,
  and an end-stage audit finds structural problems that need already-shipped
  markup rewritten. An audit branch is also the easiest thing to postpone
  indefinitely.

### Backend TDD strict; frontend test-after and targeted

- **Why:** "upstream returns 500 three times, then Redis serves stale" is far
  easier to write as a test first than to retrofit around working code, and
  failure-path tests are exactly the ones that never get written afterwards.
  PRD 11 explicitly puts the testing weight on the backend and calls the
  frontend "lighter-weight for MVP".

### Testcontainers plus WireMock, not H2 and mocks

- **Rejected:** H2 with embedded Redis (H2 is not Postgres — JSONB and upsert
  semantics differ, giving green tests over broken production); mocking the
  repository and `RedisTemplate` entirely (never exercises the SQL, the Redis
  serialisation, or the fallback ordering, which is most of what PRD 11 wants
  proven).
- **Cost accepted:** integration tests spend ~10–30s starting containers.

### PR with CI gate and squash merge

- **Rejected:** local merge without a PR — nothing would stop merging red code
  onto `main`, which auto-deploys to production.
- **Why:** solo, so there is no reviewer; the value is the gate and a `main`
  history where each commit is exactly one revertable feature.

---

## 2026-07-25 — `chore/scaffold`

### Plain servlet filter for security headers, not Spring Security

- **Rejected:** `spring-boot-starter-security`.
- **Why:** MVP has no authentication, so Spring Security would contribute only
  these headers in exchange for a filter chain to configure and extra memory
  inside a 512MB ceiling.
- **Revisit:** V2 introduces OAuth. Spring Security arrives with it and should
  take over header responsibility then.

### `Cross-Origin-Resource-Policy` deliberately omitted

The frontend (Vercel) and API (Render) are different sites, so `same-site` risks
breaking production for no benefit on a JSON API that is only ever read via
CORS-mode `fetch`. Dropped rather than set to a value that does nothing.

### HSTS emitted only for HTTPS requests

Render terminates TLS upstream, so the check is `request.isSecure() ||
X-Forwarded-Proto: https`, with `server.forward-headers-strategy: framework`.
Emitting HSTS over plain local HTTP would be misleading.

### CDS training run in the Docker build

Measured on a 512MB / 1-CPU container: **1.776s startup with the archive versus
2.698s without** (~34% faster). Cheap mitigation for the cold starts PRD 13 names
as a risk.

- **Rejected:** Paketo buildpacks (opaque when the memory calculator gets it
  wrong); GraalVM native image (5–10 minute CI builds and AOT configuration for
  JPA/Redis/Jackson — too much risk to carry into feature 1).
- **Watch out:** the training run starts the app at image-build time. Once F2
  adds Flyway, JPA and Redis it will try to reach a database and cache that do
  not exist during the build. Disable them for the training run rather than
  dropping the archive.

### JVM flags for the 512MB ceiling

`MaxRAMPercentage=70` (the ceiling covers the whole container, not just the
heap), `UseSerialGC` (no concurrent GC threads to pay for on a single CPU),
`AlwaysActAsServerClassMachine`, `Xss512k`. Measured idle usage: **105MB of
512MB**.

### `react-router` 8, not `react-router-dom`

`react-router-dom@7.18.1` pulls `react-router` inside the range of a high
severity RSC-mode CSRF advisory (GHSA-qwww-vcr4-c8h2, affects 7.12.0–8.2.0).
Not exploitable in a Vite SPA, but it would fail our own dependency-review gate.
`react-router@8.3.0` is outside the range, and since v7 the DOM APIs live in
`react-router` itself, making `react-router-dom` a redundant re-export. Result:
`npm audit` reports 0 vulnerabilities.

### `@hono/node-server` pinned via npm `overrides`

`shadcn` must stay a devDependency because `src/index.css` imports
`shadcn/tailwind.css` — it is a build input, not just a CLI. Its MCP-server
dependency chain pulls `@hono/node-server` below 2.0.5, which carries a moderate
path-traversal advisory. We never run that MCP server, but the override pins it
forward so the audit is clean rather than muted. Components are added with
`npx shadcn@latest` rather than a local CLI install.

### Five phantom devDependencies exist purely for lockfile completeness

`@emnapi/core`, `@emnapi/runtime`, `@emnapi/wasi-threads`, `@tybys/wasm-util`
and `@napi-rs/wasm-runtime` are **not used by our code**. They are the
dependencies of `@tailwindcss/oxide-wasm32-wasi`, one of the optional
platform variants of Tailwind 4's native engine.

npm records the optional variant itself in the lockfile but *not* its
sub-dependencies when resolving on a platform that does not select it. The
result: a lockfile generated on macOS makes `npm ci` fail on Linux with
"Missing: @emnapi/core from lock file", so CI breaks while local development is
fine.

- **Rejected:** generating the lockfile inside a Linux container. It works, but
  any subsequent plain `npm install` on macOS silently strips the entries again
  and the breakage only appears in CI. Verified experimentally — a local install
  took the lock from 23 `emnapi` entries down to 13.
- **Rejected:** `npm install` instead of `npm ci` in CI, which throws away
  lockfile determinism.
- **Why this instead:** direct dependencies are recorded on every platform, so
  the lockfile is now complete regardless of who installs where. Verified: lock
  is byte-identical across repeat installs, and `npm ci` exits 0 in a Linux
  container.
- **Cost accepted:** five entries a reader will not recognise, and Dependabot
  will offer to bump them (grouped under dev-dependencies). Versions are pinned
  to the ranges oxide-wasm32-wasi asks for, so npm does not install two majors
  side by side.
- **Remove when:** npm learns to record optional sub-trees cross-platform, or
  Tailwind stops shipping a wasi variant.

### Contrast measured, not assumed

All six token pairs were measured in both themes by resolving the OKLCH values
to sRGB and computing WCAG ratios. All twelve pass AA (≥4.5:1).

**Low headroom to watch:** light-theme muted text on card/page is **4.74:1** and
light-theme error text is **4.77:1**, against a 4.5 threshold. Any future
lightening of `--muted-foreground` or `--destructive` in the light theme will
break AA. Re-measure when tokens change.

### Root `tsconfig.json` carries a duplicate `paths`

The shadcn CLI reads the root `tsconfig.json`, not the referenced project
configs, and without `paths` there it writes components into a literal `@`
directory. Note that TypeScript 6 removed `baseUrl`, so `paths` is relative to
the config file and `baseUrl` must not be reintroduced.

### Deployment: verified against the live services

Provisioned 2026-07-25 and confirmed with real round-trips, not just config reads.

| Piece | Value |
|---|---|
| Backend | `https://f1-dashboard-proxy.onrender.com` — Render, Docker, `rootDir: backend`, Oregon, free |
| Health check | `/api/health` (set on the service, so a bad deploy fails fast) |
| Postgres | Neon `misty-forest-24114418`, `aws-us-east-2`, **PostgreSQL 18.4** |
| Redis | Upstash `f1-dashboard-cache`, global with primary `us-west-2`, **redis_version 8.2.0** |

Findings worth keeping:

- **Render injects `PORT` and only routes to a service listening on it.** Spring
  Boot does not read it, so the first two deploys failed in ~6 seconds with no
  open ports detected, and requests to the URL hung indefinitely rather than
  erroring. Fixed with `server.port: ${PORT:8080}`.
- **HSTS over real TLS is now confirmed working** — it could not be tested
  locally, and it validates `forward-headers-strategy: framework` plus the
  `X-Forwarded-Proto` check in `SecurityHeadersFilter`.
- **Neon's API hands back the *pooler* endpoint; we use the direct one.**
  PgBouncer transaction pooling breaks Hibernate's prepared statements, and
  HikariCP already pools. Neon's own guidance is the direct endpoint for JVM
  apps.
- **Upstash has deprecated regional databases** — creation must be `region:
  global` with a `primary_region`. A regional create returns
  `"regional db creation is deprecated"`.
- **Render's env-var API `PUT` replaces the entire set**, so every variable has
  to be sent on every update or the others are silently dropped.
- Container versions in `docker-compose.yml` were bumped from Postgres 17 /
  Redis 7 to **18 / 8** to match what the live services actually report. Drift
  here would make the Testcontainers suite prove things about the wrong
  database.

### `/api/health` is outside the `{ data, meta }` envelope

It has no season, round or upstream provenance to report. Actuator stays mounted
separately at `/actuator` and is broadened in F3.
