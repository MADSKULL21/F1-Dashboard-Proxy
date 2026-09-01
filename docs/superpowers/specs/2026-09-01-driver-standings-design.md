# Driver standings — design spec

**Feature:** F1, the first MVP vertical slice
**Branch:** `feature/driver-standings`
**Date:** 2026-09-01
**PRD sections:** 4.1, 4.7, 5, 6.2, 6.3, 8, 9, 11
**Status:** approved, ready for implementation planning

---

## 1. What this slice delivers

`GET /api/standings/drivers` and a `/standings/drivers` page rendering the
current-season driver championship: position, driver, nationality flag,
constructor, points, wins.

This is the first slice to carry real F1 data end to end, so it drags in the
upstream client, the DTO layer, the demonym mapping and the first real consumer
of the `{data, meta}` envelope the scaffold defined but never exercised.

### Explicit non-goals

Each of these is deferred deliberately, not overlooked.

| Not in this slice | Where it goes | Why |
|---|---|---|
| Podiums, DNFs columns | F8 (recent results) | Jolpica's standings response carries only `points` and `wins`. Deriving them needs the full-season results pipeline, which F8 builds anyway. PRD 4.1 already amended. |
| Driver mini-profile / click-through | F10 | PRD 4.1 bundles click-through into standings; the roadmap sequences the mini-profile last. Rows ship **not clickable** — no click affordance at all, so nothing looks broken or half-built. |
| Redis cache, Postgres snapshot | F2 (resilience chain) | No cache tier exists yet. Consequence: `DataOrigin.CACHE` and `SNAPSHOT` stay unreachable, `ApiResponse` keeps only its `live()` factory, and the PRD 4.7 "Last updated" banner cannot be built or tested in this slice because nothing can set `stale: true`. |
| Circuit breaker | F2 | With no cache there is nothing to serve while the breaker is open, so it would convert slow failures into fast ones and little else. Retry alone is the useful half. |
| Rate limiting | its own branch | Depends on Redis. Already sequenced this way in `docs/decisions.md`. |
| Constructor standings | F6 | Reuses `JolpicaConstructor` and `NationalityMapper` from this slice. |

---

## 2. Verified upstream facts

All re-verified against the live API on **2026-09-01**, not carried over from the
July planning session. Current season is **2026, round 12**.

- **Endpoint:** `GET https://api.jolpi.ca/ergast/f1/current/driverStandings/?format=json`
- **No pagination needed.** 23 drivers, default `limit` is 30. One call returns
  the whole table. (Pagination only becomes a problem at F8, ~220 result rows.)
- **Nesting is four levels deep** before reaching anything useful:
  `MRData` → `StandingsTable` → `StandingsLists[0]` → `DriverStandings[]`.
- **`Constructors` is an array and is already non-singular in live data:**
  `lawson` has two constructors this season, a real mid-season switch.
- **Points can be fractional.** 2021 returns `"395.5"` — half points are awarded
  when a race is abandoned early, which can happen in any season. Parsing
  `points` as `int` would throw.
- **`nationality` is a demonym**, never a country code. 16 distinct values on the
  current grid: Argentine, Australian, Brazilian, British, Canadian, Dutch,
  Finnish, French, German, Italian, Japanese, Mexican, Monegasque, New Zealander,
  Spanish, Thai.
- **Off-season returns HTTP 200, not an error.** A 2027 query gives
  `total: "0"`, `StandingsLists: []`, `round: null`. No error handling required
  for the empty state.
- **Every current driver has `code` and `permanentNumber`.** `position` and
  `positionText` are identical for all 23. Ties exist (Russell and Hamilton both
  on 183) and Jolpica still assigns distinct positions.
- **Upstream sends `cache-control: max-age=600`.** Jolpica caches its own
  responses for 10 minutes, so PRD 10's 1–5 minute Redis TTL buys no extra
  freshness. Flagged for F2 to revisit; out of scope here.
- **No rate-limit headers are exposed**, so retry backoff must be blind rather
  than adaptive.

### Open question, not resolvable now

Whether `/current` in January 2027 resolves to 2027 (off-season, empty) or 2026
(the completed season). A *query for 2027* behaves as documented above, but that
does not prove how `/current` resolves at a season boundary. Re-check in January.
The design tolerates either: season and round are always read back out of
`StandingsTable` rather than assumed.

---

## 3. Architecture

Layered per PRD 6.3, following the scaffold's existing `common`/`config`/`health`
package convention.

```
standings/
  DriverStandingsController      GET /api/standings/drivers
  DriverStandingsService         orchestration
  DriverStandingRow              our outward DTO — one row

jolpica/
  JolpicaClient                  RestClient + @Retry
  JolpicaProperties              base URL, connect/read timeouts
  dto/
    StandingsEnvelope            @JsonProperty("MRData")
    StandingsMrData              StandingsTable
    JolpicaStandingsTable        season, round, StandingsLists
    JolpicaStandingsList         DriverStandings[]
    JolpicaDriverStanding        position, points, wins, Driver, Constructors
    JolpicaDriver                shared leaf
    JolpicaConstructor           shared leaf

mapping/
  NationalityMapper              demonym -> ISO 3166-1 alpha-2, nullable
  DriverStandingsMapper          Jolpica DTO -> DriverStandingRow

common/
  UpstreamUnavailableException   new; see section 5
```

`jolpica/dto` is the **upstream-representation** package, not a standings-specific
one. `JolpicaDriver` and `JolpicaConstructor` are identical to what F6 and F8 will
consume, so those slices inherit them rather than redeclaring.

`NationalityMapper` is separate from `DriverStandingsMapper` because constructors
carry nationalities too and F6 will want it standalone.

### Deserialization: mirror the nesting as records

Seven records mirroring Jolpica's structure, **declaring only fields we consume**.
Jackson ignores unknown properties by default, so `url`, `positionText` and
`dateOfBirth` are never declared.

Rejected alternatives:

- **`JsonNode` path-walking in the client.** One file instead of seven, but
  navigation becomes string literals with no compile-time check, and a renamed
  upstream path yields a silent null rather than a failure. That is the exact
  failure mode PRD 7 makes a mandatory contract test to guard against.
- **A custom Jackson deserializer flattening straight to the DTO.** Fewest types,
  but hand-written deserialization is the hardest code here to test and the
  easiest to break subtly, and buys nothing the record mirror does not.

Note: Boot 4 is on **Jackson 3 (`tools.jackson`)**. Recalled Jackson 2 patterns
and annotations will not match — prefer current docs.

---

## 4. The response contract

`data` carries the rows only. Season and round live **exclusively in `meta`**,
which `ResponseMeta` already owns; duplicating them into `data` would invite
drift between two sources of the same fact.

The controller returns `ApiResponse<List<DriverStandingRow>>` and uses
`ApiResponse.live()` exactly as the scaffold defines it. No new factory.

```json
{
  "data": [
    {
      "position": 1,
      "driverId": "antonelli",
      "code": "ANT",
      "permanentNumber": 12,
      "givenName": "Andrea Kimi",
      "familyName": "Antonelli",
      "nationality": "Italian",
      "countryCode": "IT",
      "constructorId": "mercedes",
      "constructorName": "Mercedes",
      "points": 242,
      "wins": 6
    }
  ],
  "meta": {
    "season": 2026,
    "round": 12,
    "fetchedAt": "2026-09-01T16:35:10Z",
    "source": "LIVE",
    "stale": false
  }
}
```

### Field decisions

| Field | Type | Rule |
|---|---|---|
| `points` | `BigDecimal` | Fractional points are real (`395.5` in 2021). Serializes as a bare JSON number, so `242` stays `242` on the wire. |
| `wins` | `int` | Always integral. |
| `position` | `int` | Jolpica assigns distinct positions even on points ties. |
| `countryCode` | `String`, **nullable** | Null when the demonym is unmapped. |
| `constructorId` / `constructorName` | single values | Resolved as the **last** element of Jolpica's `Constructors` array. |

### Multi-constructor rule

Take the last element. This is Ergast's ordering convention (current team last),
but convention is not contract — so `lawson`'s real two-constructor payload is
captured as a WireMock fixture and asserted, meaning a silent upstream reordering
fails a test instead of mislabelling a driver in production.

Rejected: first element (would show `lawson`'s *former* team on today's live
data); returning the full list (more UI and a11y surface, and the table column
stops being a single value).

### Unmapped demonym

`countryCode` is null, the frontend omits the flag and shows nationality text
(which already carries the accessible meaning per `docs/decisions.md`), and the
backend logs a warning naming the unmapped demonym.

Rejected: failing the request (one unrecognised demonym would take down the whole
standings page — a decorative asset becoming a hard outage); a placeholder flag
(quietly wrong next to a real nationality, and hides the gap instead of
surfacing it).

### Off-season

No special code path. `StandingsLists: []` maps to `data: []` and
`meta.round: null`. The empty state is purely a frontend concern.

---

## 5. Resilience in this slice

**Resilience4j `@Retry` with exponential backoff on the Jolpica client. Nothing
else.**

Rationale: without any retry a single transient 503 gives the user an error page.
Retry is cheap, directly testable, and the failure-path test is the PRD's core
learning goal — far easier written now than retrofitted later, per
`docs/decisions.md`.

Circuit breaker, Redis and Postgres all stay at F2. Because there is no cache
tier, an exhausted retry surfaces to the user as an error. This does **not**
violate PRD 4.7, which requires cache fallback only when cached data exists;
here none does.

### One gap this exposes in the scaffold

`GlobalExceptionHandler` currently maps any unrecognised exception to
**500 `INTERNAL_ERROR`**. An exhausted retry against Jolpica is not an internal
error — it is an upstream dependency failure, and reporting it as 500 would be
dishonest and would make the frontend unable to distinguish "we are broken" from
"our data source is unreachable".

So this slice adds:

- `common/UpstreamUnavailableException` — thrown by `JolpicaClient` when retries
  are exhausted or the response is unusable.
- A handler method mapping it to **503 `SERVICE_UNAVAILABLE`** with code
  `UPSTREAM_UNAVAILABLE`, logged at warn (not error — it is not our fault and
  should not page anyone).

This is a small, genuine addition to `common/`, and it is the correct home for it
because F6–F9 will all throw the same exception.

---

## 6. Frontend

```
features/standings/
  driver-standings.tsx     the view — owns loading / empty / error branching
  standings-table.tsx      <table>, hidden md:block
  standings-cards.tsx      <ul> of cards, md:hidden
  flag.tsx                 renders nothing when countryCode is null
assets/flags/              16 vendored SVGs, named by ISO code
```

One RTK Query endpoint added to the existing `f1Api`, tag `'DriverStandings'`.
Route `/standings/drivers`, with `/` redirecting there — which retires
`ApiStatus` as the landing page.

### Mobile: card list, as two sibling trees

Below `md` the table is replaced by a card list; full driver names fit, nothing is
hidden, and there is no horizontal scroll gesture to miss.

**Implemented as two sibling trees rendered from one data shape** — a real
`<table>` with `hidden md:block`, and a `<ul>` with `md:hidden`. Screen readers
honour `display: none`, so only one is ever announced, and at 23 rows the
duplicate DOM is negligible.

**Explicitly not** a single `<table>` restyled into cards with CSS `display`
swaps — that is what strips row and column semantics from assistive tech.

Consequence for tests: both trees exist in jsdom, so `getByRole('table')` matches
even in a mobile-intent test. Tests must target the tree they mean explicitly.

Rejected: horizontal scroll (cheapest, but Wins is invisible until a gesture
users most often miss, and the scroll container needs `tabindex="0"` plus an
accessible name to be keyboard-reachable); column priority (drops Wins entirely
on mobile, and the hiding rule needs revisiting when F8 adds two more columns).

### Flags

16 SVGs vendored into `src/assets/flags/` from a public-domain set, named by the
ISO code the backend returns. No runtime dependency, no network fetch, CSP stays
`default-src 'self'`. Add flags as new nationalities appear.

Rejected: an npm flag package (ships hundreds of unused assets and adds a
dependency to a repo whose audit hygiene is already being tightened); text-only
(drops something PRD 4.1 explicitly asks for).

---

## 7. Implementation steps

Eleven commits. Each ends green and is provable on its own. Error handling and
testing are **per-step acceptance criteria**, not separate late phases.

Steps 1–6 are strict TDD; 7–10 are test-after and targeted, per PRD 11 and
`docs/decisions.md`. Steps 1 and 2 are the only pair with no dependency between
them; everything else is sequential.

### Backend

**1 · Upstream contract types** — the 7 `jolpica/dto` records.
*Done when:* a real Jolpica response captured 2026-09-01 (round 12) deserializes
into populated records. Pure unit test, no Spring context. Locks the upstream
shape before anything depends on it.

**2 · NationalityMapper** — 16-entry demonym→ISO map, nullable on miss + warning.
*Done when:* every nationality on the current grid resolves; an unknown demonym
returns null without throwing. Pure unit test.

**3 · JolpicaClient, no resilience yet** — `RestClient` + `JolpicaProperties`
(base URL, connect and read timeouts).
*Done when:* a WireMock stub returns the fixture and the client produces a
populated envelope. Real HTTP round trip, stubbed host.

**4 · Resilience4j retry + `UpstreamUnavailableException`** — dependency, config
block, `@Retry` on the client, the new exception and its handler method mapping to
503 `UPSTREAM_UNAVAILABLE` (section 5).
*Done when:* stub sequence 503 → 503 → 200 succeeds and records 3 attempts;
exhausted retries produce `UpstreamUnavailableException`; and a controller-level
test asserts the client sees **503**, not 500. Kept separate from step 3 so the
retry test proves the retry is doing something rather than passing vacuously.

**5 · DriverStandingsMapper** — where the business rules live.
*Done when:* four tests pass — last-constructor wins against `lawson`'s real
payload; `395.5` survives as `BigDecimal`; an unknown demonym yields a null
`countryCode`; empty `StandingsLists` yields an empty list.

**6 · Service + controller + endpoint**
*Done when:* `GET /api/standings/drivers` via MockMvc + WireMock returns the full
`{data, meta}` envelope with season and round read out of `StandingsTable`; and
the off-season stub returns `[]` with `meta.round: null`. (The 503 upstream-failure
path is already asserted in step 4.)
**Backend complete — curl-demoable.**

### Frontend

**7 · RTK Query endpoint + TS types** mirroring the DTO, tag `'DriverStandings'`.
*Done when:* typecheck passes and the endpoint is reachable from a test store.

**8 · Flag component + 16 vendored SVGs**
*Done when:* renders an image for `IT`; renders nothing for `null`; is decorative
(empty alt or `aria-hidden`) so nationality text carries the meaning.

**9 · Table + cards** — two sibling trees, one data shape.
*Done when:* both render the same rows; tests target each tree explicitly rather
than relying on `getByRole('table')`; the table has a caption and `scope`
attributes; keyboard traversal works.

**10 · The view + route** — skeleton, off-season empty state, error state,
`/standings/drivers`, `/` redirect.
*Done when:* all three states have a test, and the off-season state shows the
explicit "season hasn't started" copy PRD 4.1 requires.
**Feature complete — browser-demoable.**

**11 · Definition of done** — AA contrast measured in both themes, a
`docs/decisions.md` entry, README roadmap line moved.
*Done when:* contrast ratios are recorded as numbers, not asserted as "looks
fine". `docs/decisions.md` already flags light-theme muted text at 4.74:1 against
a 4.5 threshold, so any new muted cell in this table needs re-measuring.

---

## 8. Testing

Per PRD 11 and `docs/decisions.md`: Testcontainers plus WireMock rather than H2
and mocks. This slice needs **WireMock only** — no database or cache is involved
until F2.

| Layer | Tool | What it proves |
|---|---|---|
| `jolpica/dto` | plain unit | The real captured payload deserializes correctly. |
| `NationalityMapper` | plain unit | All 16 grid demonyms resolve; unknown returns null. |
| `DriverStandingsMapper` | plain unit | Multi-constructor, fractional points, null country code, empty season. |
| `JolpicaClient` | WireMock | HTTP round trip; retry on 503; typed failure when exhausted. |
| Controller | MockMvc + WireMock | Envelope shape, `meta` provenance, off-season, and 503 `UPSTREAM_UNAVAILABLE` on exhausted retries. |
| Frontend | Vitest + Testing Library | Loading, empty, error; both presentation trees; flag null case; keyboard operation. |

Fixtures are captured from the **live API**, not hand-written, so they cannot
drift into describing an API that does not exist. `lawson`'s two-constructor
payload is a fixture in its own right.

### Note on PRD 7's mandatory contract test

PRD 7 and `docs/decisions.md` require a contract test asserting that the
Jolpica→DTO and rows→DTO paths produce equivalent DTOs from the same fixture.
**That test belongs to F2**, because the rows→DTO path does not exist until the
Postgres snapshot does. The scaffold's existing
`ApiResponseContractIntegrationTest` covers envelope *serialization* only and is
not that test. Recorded here so it is not mistaken for done.

---

## 9. Cost and risk

Counted from section 3, this slice adds **15 backend production files** — 3
`standings`, 2 `jolpica`, 7 `jolpica/dto`, 2 `mapping`, 1 `common` — plus about 5
test classes and their captured fixtures. On the frontend: **4 new components**,
their tests, and 16 flag assets, plus edits to `store/api.ts` and `App.tsx`.

A large first feature. That is the direct consequence of the vertical-slice
decision in `docs/decisions.md`, which front-loads scaffolding into the first
slice. Accepted knowingly.

Two carried risks from `docs/decisions.md` that this slice touches:

- **The Docker CDS training run starts the app at image-build time.** This slice
  adds no database or cache, so the training run still succeeds — but F2 will
  break it. Not this slice's problem; noted so it is not a surprise.
- **`/current` at a season boundary** is unproven (section 2). Mitigated by
  always reading season and round back out of `StandingsTable`.
