# Driver Standings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `GET /api/standings/drivers` and a `/standings/drivers` page showing the current-season F1 driver championship, with retry-backed upstream access.

**Architecture:** Layered backend — Controller → Service → Client → DTOs — with Jolpica's response mirrored as records and mapped to an outward DTO. Frontend renders one data shape through two sibling presentations (a real `<table>` on desktop, a card `<ul>` on mobile). No cache tier exists yet; retry is the only resilience mechanism in this slice.

**Tech Stack:** Spring Boot 4.1.0, Java 25, Jackson 3, Resilience4j 2.3.0, WireMock 3.13.1, React 19, Redux Toolkit / RTK Query, Tailwind 4, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-01-driver-standings-design.md`

---

## Global Constraints

Every task's requirements implicitly include this section.

**Jackson 3 package split — the single most likely thing to get wrong.** Boot 4 uses Jackson 3, but the split is counterintuitive and was verified against the resolved dependency tree:
- Annotations **stayed on Jackson 2 coordinates**: `@JsonProperty` is `com.fasterxml.jackson.annotation.JsonProperty` (artifact `com.fasterxml.jackson.core:jackson-annotations:2.21`).
- Databind **moved**: `ObjectMapper` is `tools.jackson.databind.ObjectMapper` (artifact `tools.jackson.core:jackson-databind:3.1.4`).
- `tools.jackson.core.JacksonException extends RuntimeException` — **unchecked**. There is no checked `JsonProcessingException` to declare. Do not write `throws` clauses for deserialization.

**Resilience4j coordinates — PRD §6.3 is wrong.** It names `resilience4j-spring-boot4` version `2.4.0`. Neither exists. Verified on Maven Central: the newest published starter is `resilience4j-spring-boot3`, newest version `2.3.0`. Use:
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.3.0</version>
</dependency>
```
This Boot 3 starter **was empirically verified to work on Boot 4.1.0 / Java 25** — a throwaway spike confirmed `@Retry` intercepts and records 3 attempts. Its autoconfiguration registration file and the `@ConditionalOnClass` / `@EnableConfigurationProperties` annotations it depends on are all still present in Boot 4. Treat it as unsupported-but-verified and re-check on every Boot upgrade.

**AOP starter renamed in Boot 4.** `spring-boot-starter-aop` has no 4.x release. Resilience4j's aspects need `spring-boot-starter-aspectj` instead (managed by the Boot 4 parent, so no `<version>`).

**Resilience4j self-invocation trap.** `@Retry` works through a Spring proxy, so a method calling another method on `this` bypasses it entirely. `JolpicaClient` therefore lets `RestClientException` propagate out of the annotated method, and `DriverStandingsService` — a **different bean** — catches and translates it. Never try to catch-and-wrap inside `JolpicaClient`.

**Java / build:** Java 25, Maven via `./mvnw`. Existing `@ConfigurationProperties` pattern is a record plus `@EnableConfigurationProperties` on a `@Configuration` class — follow `config/CorsProperties.java` and `config/CorsConfig.java`.

**Frontend:** path alias `@/` → `frontend/src`. Lint is `oxlint`. Tests are Vitest with `renderWithProviders` from `@/test/render`. Fetch is stubbed via `vi.stubGlobal('fetch', …)` — do **not** add MSW.

**Every commit must leave both suites green:** `cd backend && ./mvnw verify` and `cd frontend && npm run lint && npm run typecheck && npm test && npm run build`.

**Accessibility:** WCAG 2.1 AA. Semantic markup, keyboard operable, contrast verified in both themes. Flags are decorative; nationality text carries the meaning.

**Backend is strict TDD (Tasks 1–6).** Frontend is test-after and targeted (Tasks 7–11), per PRD §11.

---

## File Structure

### Backend — create

| File | Responsibility |
|---|---|
| `jolpica/dto/StandingsEnvelope.java` | `MRData` wrapper |
| `jolpica/dto/StandingsMrData.java` | `StandingsTable` wrapper |
| `jolpica/dto/JolpicaStandingsTable.java` | season, round, `StandingsLists` |
| `jolpica/dto/JolpicaStandingsList.java` | `DriverStandings` array |
| `jolpica/dto/JolpicaDriverStanding.java` | one standings row as upstream sends it |
| `jolpica/dto/JolpicaDriver.java` | shared leaf — reused by F6/F8 |
| `jolpica/dto/JolpicaConstructor.java` | shared leaf — reused by F6/F8 |
| `jolpica/JolpicaProperties.java` | base URL, timeouts |
| `jolpica/JolpicaClient.java` | `RestClient` + `@Retry` |
| `mapping/NationalityMapper.java` | demonym → ISO alpha-2, nullable |
| `mapping/DriverStandingsMapper.java` | upstream DTO → outward DTO |
| `standings/DriverStandingRow.java` | outward DTO, one row |
| `standings/DriverStandingsService.java` | orchestration, envelope navigation, error translation |
| `standings/DriverStandingsController.java` | `GET /api/standings/drivers` |
| `common/UpstreamUnavailableException.java` | upstream failure, maps to 503 |

### Backend — modify

| File | Change |
|---|---|
| `pom.xml` | add resilience4j, aspectj starter, wiremock |
| `src/main/resources/application.yml` | add `app.jolpica.*` and `resilience4j.retry.*` |
| `common/GlobalExceptionHandler.java` | add `UpstreamUnavailableException` handler |

### Frontend — create

| File | Responsibility |
|---|---|
| `src/features/standings/driver-standings.tsx` | view; owns loading / empty / error branching |
| `src/features/standings/standings-table.tsx` | `<table>`, `hidden md:block` |
| `src/features/standings/standings-cards.tsx` | card `<ul>`, `md:hidden` |
| `src/features/standings/flag.tsx` | decorative flag, renders nothing when code is null |
| `src/test/fetch.ts` | shared fetch-stub helper |
| `public/flags/*.svg` | 16 flags, named by lowercase ISO code |

### Frontend — modify

| File | Change |
|---|---|
| `src/store/api.ts` | add `DriverStandingRow` type + `driverStandings` endpoint |
| `src/App.tsx` | add `/standings/drivers` route and `/` redirect |
| `src/features/health/api-status.test.tsx` | use the extracted fetch helper |

**Flag hosting deviates from the spec** (which said `src/assets/flags/`): flags go in `public/flags/` and are referenced by path. Vite's asset imports need static specifiers, and the filename here is computed from the backend's ISO code at runtime. `public/` avoids an import map for 16 tiny files, and CSP `img-src 'self'` already permits it.

---

## Task 1: Upstream contract records

**Files:**
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/StandingsEnvelope.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/StandingsMrData.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/JolpicaStandingsTable.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/JolpicaStandingsList.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/JolpicaDriverStanding.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/JolpicaDriver.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/dto/JolpicaConstructor.java`
- Test: `backend/src/test/java/com/f1dashboard/api/jolpica/dto/StandingsEnvelopeTest.java`
- Test fixture: `backend/src/test/resources/fixtures/jolpica/driver-standings-2026-r12.json`
- Test fixture: `backend/src/test/resources/fixtures/jolpica/driver-standings-offseason.json`

**Interfaces:**
- Consumes: nothing.
- Produces: `StandingsEnvelope.mrData().standingsTable()` → `JolpicaStandingsTable` with `season()`, `round()`, `standingsLists()`. `JolpicaStandingsList.driverStandings()` → `List<JolpicaDriverStanding>`, each with `position()`, `points()`, `wins()`, `driver()`, `constructors()`. All scalar fields are `String` — upstream sends every number quoted. `JolpicaDriver` exposes `driverId()`, `permanentNumber()`, `code()`, `givenName()`, `familyName()`, `nationality()`. `JolpicaConstructor` exposes `constructorId()`, `name()`.

- [ ] **Step 1: Create the two fixtures**

`driver-standings-2026-r12.json` — real payload captured 2026-09-01, trimmed to three rows. Antonelli is the leader, and `lawson` is included because he is the live two-constructor case Task 5 depends on.

```json
{
  "MRData": {
    "series": "f1",
    "limit": "30",
    "offset": "0",
    "total": "23",
    "StandingsTable": {
      "season": "2026",
      "round": "12",
      "StandingsLists": [
        {
          "season": "2026",
          "round": "12",
          "DriverStandings": [
            {
              "position": "1",
              "positionText": "1",
              "points": "242",
              "wins": "6",
              "Driver": {
                "driverId": "antonelli",
                "permanentNumber": "12",
                "code": "ANT",
                "url": "https://en.wikipedia.org/wiki/Andrea_Kimi_Antonelli",
                "givenName": "Andrea Kimi",
                "familyName": "Antonelli",
                "dateOfBirth": "2006-08-25",
                "nationality": "Italian"
              },
              "Constructors": [
                {
                  "constructorId": "mercedes",
                  "url": "https://en.wikipedia.org/wiki/Mercedes-Benz_in_Formula_One",
                  "name": "Mercedes",
                  "nationality": "German"
                }
              ]
            },
            {
              "position": "9",
              "positionText": "9",
              "points": "49",
              "wins": "0",
              "Driver": {
                "driverId": "lawson",
                "permanentNumber": "30",
                "code": "LAW",
                "url": "http://en.wikipedia.org/wiki/Liam_Lawson",
                "givenName": "Liam",
                "familyName": "Lawson",
                "dateOfBirth": "2002-02-11",
                "nationality": "New Zealander"
              },
              "Constructors": [
                {
                  "constructorId": "rb",
                  "url": "https://en.wikipedia.org/wiki/Racing_Bulls",
                  "name": "RB F1 Team",
                  "nationality": "Italian"
                },
                {
                  "constructorId": "red_bull",
                  "url": "https://en.wikipedia.org/wiki/Red_Bull_Racing",
                  "name": "Red Bull",
                  "nationality": "Austrian"
                }
              ]
            },
            {
              "position": "20",
              "positionText": "20",
              "points": "0.5",
              "wins": "0",
              "Driver": {
                "driverId": "fictional",
                "permanentNumber": "99",
                "code": "FIC",
                "url": "https://example.invalid",
                "givenName": "Half",
                "familyName": "Point",
                "dateOfBirth": "2000-01-01",
                "nationality": "Atlantean"
              },
              "Constructors": [
                {
                  "constructorId": "atlantis",
                  "url": "https://example.invalid",
                  "name": "Atlantis Racing",
                  "nationality": "Atlantean"
                }
              ]
            }
          ]
        }
      ]
    }
  }
}
```

The third row is deliberately synthetic — it carries fractional points (`"0.5"`) and an unmappable nationality (`"Atlantean"`) so Task 5 can assert both edge cases from one fixture. Rows one and two are byte-faithful to the live API.

`driver-standings-offseason.json` — real shape of a future-season query, verified 2026-09-01:

```json
{
  "MRData": {
    "series": "f1",
    "limit": "30",
    "offset": "0",
    "total": "0",
    "StandingsTable": {
      "season": "2027",
      "round": null,
      "StandingsLists": []
    }
  }
}
```

- [ ] **Step 2: Write the failing test**

`StandingsEnvelopeTest.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the records match what Jolpica actually sends. The fixture is captured
 * from the live API rather than hand-written, so it cannot drift into describing
 * an API that does not exist.
 */
class StandingsEnvelopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private StandingsEnvelope read(String fixture) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/jolpica/" + fixture)) {
            assertThat(in).as("fixture %s must exist", fixture).isNotNull();
            return mapper.readValue(in, StandingsEnvelope.class);
        }
    }

    @Test
    void deserialisesSeasonAndRoundFromStandingsTable() throws IOException {
        JolpicaStandingsTable table = read("driver-standings-2026-r12.json").mrData().standingsTable();

        assertThat(table.season()).isEqualTo("2026");
        assertThat(table.round()).isEqualTo("12");
        assertThat(table.standingsLists()).hasSize(1);
    }

    @Test
    void deserialisesDriverAndConstructorLeaves() throws IOException {
        JolpicaDriverStanding leader = read("driver-standings-2026-r12.json")
                .mrData().standingsTable().standingsLists().getFirst().driverStandings().getFirst();

        assertThat(leader.position()).isEqualTo("1");
        assertThat(leader.points()).isEqualTo("242");
        assertThat(leader.wins()).isEqualTo("6");
        assertThat(leader.driver().driverId()).isEqualTo("antonelli");
        assertThat(leader.driver().code()).isEqualTo("ANT");
        assertThat(leader.driver().permanentNumber()).isEqualTo("12");
        assertThat(leader.driver().givenName()).isEqualTo("Andrea Kimi");
        assertThat(leader.driver().familyName()).isEqualTo("Antonelli");
        assertThat(leader.driver().nationality()).isEqualTo("Italian");
        assertThat(leader.constructors()).hasSize(1);
        assertThat(leader.constructors().getFirst().constructorId()).isEqualTo("mercedes");
        assertThat(leader.constructors().getFirst().name()).isEqualTo("Mercedes");
    }

    @Test
    void keepsConstructorOrderingForDriversWhoSwitchedTeams() throws IOException {
        JolpicaDriverStanding lawson = read("driver-standings-2026-r12.json")
                .mrData().standingsTable().standingsLists().getFirst().driverStandings().get(1);

        assertThat(lawson.driver().driverId()).isEqualTo("lawson");
        assertThat(lawson.constructors()).hasSize(2);
        assertThat(lawson.constructors().get(0).constructorId()).isEqualTo("rb");
        assertThat(lawson.constructors().get(1).constructorId()).isEqualTo("red_bull");
    }

    @Test
    void deserialisesOffSeasonAsEmptyListWithNullRound() throws IOException {
        JolpicaStandingsTable table = read("driver-standings-offseason.json").mrData().standingsTable();

        assertThat(table.season()).isEqualTo("2027");
        assertThat(table.round()).isNull();
        assertThat(table.standingsLists()).isEmpty();
    }

    @Test
    void ignoresUpstreamFieldsWeDoNotDeclare() throws IOException {
        // url, positionText and dateOfBirth are present in the fixture and
        // deliberately absent from the records. Deserialisation must not fail.
        assertThat(read("driver-standings-2026-r12.json")).isNotNull();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=StandingsEnvelopeTest`
Expected: FAIL — compilation error, `StandingsEnvelope` does not exist.

- [ ] **Step 4: Create the seven records**

`StandingsEnvelope.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jolpica's outermost wrapper. Every Ergast-compatible response is shaped
 * {@code { "MRData": { ... } }}.
 */
public record StandingsEnvelope(@JsonProperty("MRData") StandingsMrData mrData) {
}
```

`StandingsMrData.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Only the payload we consume is declared. {@code series}, {@code limit},
 * {@code offset} and {@code total} are present upstream and deliberately dropped:
 * pagination is unnecessary here (23 drivers against a default limit of 30).
 */
public record StandingsMrData(@JsonProperty("StandingsTable") JolpicaStandingsTable standingsTable) {
}
```

`JolpicaStandingsTable.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param season         the season Jolpica resolved. Read back out rather than
 *                       assumed from the calendar, so a season boundary cannot
 *                       silently mislabel data.
 * @param round          latest round covered, or {@code null} off-season.
 * @param standingsLists empty off-season; otherwise exactly one entry.
 */
public record JolpicaStandingsTable(
        String season,
        String round,
        @JsonProperty("StandingsLists") List<JolpicaStandingsList> standingsLists) {
}
```

`JolpicaStandingsList.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JolpicaStandingsList(
        String season,
        String round,
        @JsonProperty("DriverStandings") List<JolpicaDriverStanding> driverStandings) {
}
```

`JolpicaDriverStanding.java`:

```java
package com.f1dashboard.api.jolpica.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One standings row exactly as upstream sends it — every number quoted as a
 * String. Conversion happens in the mapper, not here, so a malformed upstream
 * value fails in one place with one message.
 *
 * @param constructors ordered; the driver's current team is the last element.
 *                     Non-singular for anyone who switched teams mid-season.
 */
public record JolpicaDriverStanding(
        String position,
        String points,
        String wins,
        @JsonProperty("Driver") JolpicaDriver driver,
        @JsonProperty("Constructors") List<JolpicaConstructor> constructors) {
}
```

`JolpicaDriver.java`:

```java
package com.f1dashboard.api.jolpica.dto;

/**
 * Shared leaf. Identical in the results and constructor-standings responses, so
 * F6 and F8 reuse this rather than redeclaring it.
 *
 * @param nationality a demonym ("Italian"), never a country code.
 */
public record JolpicaDriver(
        String driverId,
        String permanentNumber,
        String code,
        String givenName,
        String familyName,
        String nationality) {
}
```

`JolpicaConstructor.java`:

```java
package com.f1dashboard.api.jolpica.dto;

/** Shared leaf. Reused by F6 (constructor standings) and F8 (results). */
public record JolpicaConstructor(
        String constructorId,
        String name) {
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=StandingsEnvelopeTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 19 tests (14 existing + 5 new).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/f1dashboard/api/jolpica/dto \
        backend/src/test/java/com/f1dashboard/api/jolpica/dto \
        backend/src/test/resources/fixtures
git commit -m "feat(jolpica): mirror the driver standings response as records

Declares only the fields we consume; Jackson ignores the rest. Fixtures are
captured from the live API on 2026-09-01 so they cannot drift into describing
an API that does not exist.

Note the Jackson 3 package split: annotations stay at
com.fasterxml.jackson.annotation, databind moves to tools.jackson.databind."
```

---

## Task 2: NationalityMapper

**Files:**
- Create: `backend/src/main/java/com/f1dashboard/api/mapping/NationalityMapper.java`
- Test: `backend/src/test/java/com/f1dashboard/api/mapping/NationalityMapperTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `NationalityMapper` — a Spring `@Component` with one method, `String toIsoCode(String demonym)`, returning a two-letter uppercase ISO 3166-1 alpha-2 code, or `null` when the demonym is unmapped or blank.

- [ ] **Step 1: Write the failing test**

```java
package com.f1dashboard.api.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * The 16 demonyms are every nationality on the 2026 grid, verified against the
 * live API on 2026-09-01. An unmapped demonym must degrade to a missing flag,
 * never to a failed request — a decorative asset must not become an outage.
 */
class NationalityMapperTest {

    private final NationalityMapper mapper = new NationalityMapper();

    @ParameterizedTest
    @CsvSource({
            "Argentine,AR",
            "Australian,AU",
            "Brazilian,BR",
            "British,GB",
            "Canadian,CA",
            "Dutch,NL",
            "Finnish,FI",
            "French,FR",
            "German,DE",
            "Italian,IT",
            "Japanese,JP",
            "Mexican,MX",
            "Monegasque,MC",
            "New Zealander,NZ",
            "Spanish,ES",
            "Thai,TH",
    })
    void mapsEveryNationalityOnTheCurrentGrid(String demonym, String expectedIso) {
        assertThat(mapper.toIsoCode(demonym)).isEqualTo(expectedIso);
    }

    @Test
    void coversTheWholeCurrentGridWithNoGaps() {
        List<String> grid = List.of(
                "Argentine", "Australian", "Brazilian", "British", "Canadian", "Dutch",
                "Finnish", "French", "German", "Italian", "Japanese", "Mexican",
                "Monegasque", "New Zealander", "Spanish", "Thai");

        assertThat(grid).allSatisfy(demonym ->
                assertThat(mapper.toIsoCode(demonym)).as("demonym %s", demonym).isNotNull());
    }

    @Test
    void returnsNullForAnUnmappedDemonym() {
        assertThat(mapper.toIsoCode("Atlantean")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void returnsNullForMissingInputWithoutThrowing(String demonym) {
        assertThat(mapper.toIsoCode(demonym)).isNull();
    }

    @Test
    void isCaseAndWhitespaceTolerant() {
        // Cheap insurance against upstream changing "British" to "british".
        assertThat(mapper.toIsoCode("  british  ")).isEqualTo("GB");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=NationalityMapperTest`
Expected: FAIL — compilation error, `NationalityMapper` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.f1dashboard.api.mapping;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps Jolpica's nationality demonyms to ISO 3166-1 alpha-2 codes.
 *
 * <p>Jolpica returns "Italian", not "IT", and demonym-to-code is data
 * normalisation, so it belongs in the layer with the stricter test discipline
 * rather than in a frontend lookup table.
 *
 * <p>An unknown demonym yields {@code null} and a warning. The frontend then
 * omits the flag and shows the nationality text, which carries the accessible
 * meaning anyway — one row loses decoration, the table still works.
 */
@Component
public class NationalityMapper {

    private static final Logger log = LoggerFactory.getLogger(NationalityMapper.class);

    /**
     * Every nationality on the 2026 grid, verified against the live API.
     * Keys are lowercase; lookups are normalised to match.
     */
    private static final Map<String, String> ISO_BY_DEMONYM = Map.ofEntries(
            Map.entry("argentine", "AR"),
            Map.entry("australian", "AU"),
            Map.entry("brazilian", "BR"),
            Map.entry("british", "GB"),
            Map.entry("canadian", "CA"),
            Map.entry("dutch", "NL"),
            Map.entry("finnish", "FI"),
            Map.entry("french", "FR"),
            Map.entry("german", "DE"),
            Map.entry("italian", "IT"),
            Map.entry("japanese", "JP"),
            Map.entry("mexican", "MX"),
            Map.entry("monegasque", "MC"),
            Map.entry("new zealander", "NZ"),
            Map.entry("spanish", "ES"),
            Map.entry("thai", "TH"));

    /**
     * @param demonym Jolpica's nationality string, e.g. "Monegasque"
     * @return the ISO 3166-1 alpha-2 code, or {@code null} if unmapped or blank
     */
    public String toIsoCode(String demonym) {
        if (demonym == null || demonym.isBlank()) {
            return null;
        }

        String iso = ISO_BY_DEMONYM.get(demonym.strip().toLowerCase());
        if (iso == null) {
            log.warn("No ISO country code mapped for nationality '{}' — flag will be omitted. "
                    + "Add it to NationalityMapper and vendor the SVG.", demonym);
        }
        return iso;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=NationalityMapperTest`
Expected: PASS, 21 tests (16 parameterised + 5).

- [ ] **Step 5: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 40 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/f1dashboard/api/mapping/NationalityMapper.java \
        backend/src/test/java/com/f1dashboard/api/mapping/NationalityMapperTest.java
git commit -m "feat(mapping): map nationality demonyms to ISO country codes

Covers all 16 nationalities on the 2026 grid. An unmapped demonym degrades to
a null code and a warning rather than failing the request — a decorative flag
must not become an outage."
```

---

## Task 3: JolpicaClient

**Files:**
- Modify: `backend/pom.xml` (add WireMock)
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaProperties.java`
- Create: `backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaClient.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/f1dashboard/api/jolpica/JolpicaClientTest.java`

**Interfaces:**
- Consumes: `StandingsEnvelope` and its nested records from Task 1.
- Produces: `JolpicaClient.fetchCurrentDriverStandings()` returning `StandingsEnvelope`. Throws `org.springframework.web.client.RestClientException` on transport failure or non-2xx — **deliberately unwrapped**, so Task 4's `@Retry` can see it and Task 6's service can translate it.

- [ ] **Step 1: Add the WireMock dependency**

In `backend/pom.xml`, after the existing test dependencies:

```xml
		<dependency>
			<groupId>org.wiremock</groupId>
			<artifactId>wiremock-standalone</artifactId>
			<version>3.13.1</version>
			<scope>test</scope>
		</dependency>
```

Version is pinned explicitly because the Boot 4 parent does not manage WireMock. 3.13.1 is the newest stable release — 4.0.0 is still beta.

- [ ] **Step 2: Write the failing test**

```java
package com.f1dashboard.api.jolpica;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import com.f1dashboard.api.jolpica.dto.StandingsEnvelope;

/**
 * Exercises the real HTTP path against a stubbed host. Mocking RestClient would
 * prove nothing about URL construction or deserialisation, which is most of what
 * this class does.
 */
class JolpicaClientTest {

    private static WireMockServer wireMock;
    private JolpicaClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        JolpicaProperties properties = new JolpicaProperties(
                wireMock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        client = new JolpicaClient(properties);
    }

    private static String fixture() throws IOException {
        try (InputStream in = JolpicaClientTest.class
                .getResourceAsStream("/fixtures/jolpica/driver-standings-2026-r12.json")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    void fetchesAndDeserialisesCurrentDriverStandings() throws IOException {
        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        StandingsEnvelope envelope = client.fetchCurrentDriverStandings();

        assertThat(envelope.mrData().standingsTable().season()).isEqualTo("2026");
        assertThat(envelope.mrData().standingsTable().standingsLists()
                .getFirst().driverStandings()).hasSize(3);
    }

    @Test
    void requestsJsonFormatExplicitly() throws IOException {
        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        client.fetchCurrentDriverStandings();

        wireMock.verify(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/current/driverStandings/"))
                .withQueryParam("format", com.github.tomakehurst.wiremock.client.WireMock.equalTo("json")));
    }

    @Test
    void propagatesRestClientExceptionOnServerError() {
        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .willReturn(aResponse().withStatus(503)));

        // Unwrapped on purpose: Task 4's @Retry must be able to see this, and the
        // service layer translates it. Wrapping here would defeat both.
        assertThatThrownBy(() -> client.fetchCurrentDriverStandings())
                .isInstanceOf(RestClientException.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=JolpicaClientTest`
Expected: FAIL — compilation error, `JolpicaProperties` and `JolpicaClient` do not exist.

- [ ] **Step 4: Create JolpicaProperties**

```java
package com.f1dashboard.api.jolpica;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl        Jolpica's Ergast-compatible base, without a trailing slash
 * @param connectTimeout how long to wait for the TCP connection
 * @param readTimeout    how long to wait for the response body. Kept short: a
 *                       hanging upstream must not hold a request thread open,
 *                       and retries are cheap.
 */
@ConfigurationProperties(prefix = "app.jolpica")
public record JolpicaProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}
```

- [ ] **Step 5: Create JolpicaClient**

```java
package com.f1dashboard.api.jolpica;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.f1dashboard.api.jolpica.dto.StandingsEnvelope;

/**
 * The only class that talks to Jolpica. PRD 6.1 makes the backend the sole
 * mediator of external data, so every upstream call funnels through here.
 *
 * <p>The RestClient is built explicitly rather than injecting an autoconfigured
 * builder: Boot 4 moved RestClient autoconfiguration into its own module, and an
 * explicit factory makes the timeouts visible at the call site instead of hiding
 * them in properties resolution.
 *
 * <p>Exceptions are deliberately <strong>not</strong> wrapped here. Task 4 adds
 * {@code @Retry} to this method, which works through a Spring proxy — catching
 * inside would hide failures from the retry. Translation to
 * {@code UpstreamUnavailableException} happens in the service, a different bean.
 */
@Component
@EnableConfigurationProperties(JolpicaProperties.class)
public class JolpicaClient {

    private static final String DRIVER_STANDINGS_PATH = "/current/driverStandings/";

    private final RestClient restClient;

    JolpicaClient(JolpicaProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * @return the current season's driver standings exactly as Jolpica sends them
     * @throws org.springframework.web.client.RestClientException on transport
     *         failure or any non-2xx status
     */
    public StandingsEnvelope fetchCurrentDriverStandings() {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(DRIVER_STANDINGS_PATH)
                        // Jolpica defaults to XML; ask for JSON explicitly rather
                        // than relying on content negotiation.
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(StandingsEnvelope.class);
    }
}
```

- [ ] **Step 6: Add configuration to application.yml**

Insert after the `app.version` line, inside the existing `app:` block:

```yaml
  jolpica:
    # PRD 6.4. Trailing slash omitted; paths supply their own.
    base-url: https://api.jolpi.ca/ergast/f1
    # Short on purpose: a hanging upstream must not hold a request thread, and
    # retry is cheaper than waiting.
    connect-timeout: 3s
    read-timeout: 5s
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=JolpicaClientTest`
Expected: PASS, 3 tests.

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 43 tests.

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
        backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaClient.java \
        backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaProperties.java \
        backend/src/test/java/com/f1dashboard/api/jolpica/JolpicaClientTest.java
git commit -m "feat(jolpica): fetch current driver standings over HTTP

RestClient built explicitly with visible timeouts rather than via the
autoconfigured builder, which Boot 4 moved to a separate module. Exceptions
propagate unwrapped so the retry added next can see them.

Tested against WireMock rather than a mocked RestClient: mocking would prove
nothing about URL construction or deserialisation."
```

---

## Task 4: Retry and the 503 contract

**Files:**
- Modify: `backend/pom.xml` (resilience4j + aspectj starter)
- Modify: `backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaClient.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/f1dashboard/api/common/UpstreamUnavailableException.java`
- Modify: `backend/src/main/java/com/f1dashboard/api/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/f1dashboard/api/jolpica/JolpicaClientRetryTest.java`
- Test: `backend/src/test/java/com/f1dashboard/api/common/UpstreamUnavailableIntegrationTest.java`

**Interfaces:**
- Consumes: `JolpicaClient.fetchCurrentDriverStandings()` from Task 3.
- Produces: `UpstreamUnavailableException(String message, Throwable cause)`, a `RuntimeException`. `GlobalExceptionHandler` maps it to HTTP **503** with `ApiError.code` `"UPSTREAM_UNAVAILABLE"`. `JolpicaClient.fetchCurrentDriverStandings()` is now annotated `@Retry(name = "jolpica")` and attempts 3 times before propagating.

- [ ] **Step 1: Add the dependencies**

In `backend/pom.xml`, in the main (non-test) dependency block:

```xml
		<dependency>
			<groupId>io.github.resilience4j</groupId>
			<artifactId>resilience4j-spring-boot3</artifactId>
			<version>2.3.0</version>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-aspectj</artifactId>
		</dependency>
```

`resilience4j-spring-boot3` — **not** `-spring-boot4`, which PRD §6.3 names but which does not exist. `spring-boot-starter-aspectj` — **not** `-aop`, which has no Boot 4 release. Both facts verified against Maven Central; the combination was verified working on Boot 4.1.0 / Java 25 by spike.

- [ ] **Step 2: Write the failing retry test**

```java
package com.f1dashboard.api.jolpica;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

/**
 * @Retry works through a Spring proxy, so this must be a Spring context test —
 * a plain {@code new JolpicaClient(...)} would silently exercise no retry at all
 * and pass for the wrong reason.
 */
@SpringBootTest(properties = {
        "resilience4j.retry.instances.jolpica.max-attempts=3",
        "resilience4j.retry.instances.jolpica.wait-duration=10ms",
})
class JolpicaClientRetryTest {

    private static WireMockServer wireMock;

    @Autowired
    JolpicaClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void jolpicaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jolpica.base-url", () -> wireMock.baseUrl());
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private static String fixture() throws IOException {
        try (InputStream in = JolpicaClientRetryTest.class
                .getResourceAsStream("/fixtures/jolpica/driver-standings-2026-r12.json")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    void recoversAfterTwoServerErrors() throws IOException {
        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));

        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));

        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture())));

        assertThat(client.fetchCurrentDriverStandings().mrData().standingsTable().season())
                .isEqualTo("2026");

        wireMock.verify(3, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/current/driverStandings/")));
    }

    @Test
    void givesUpAfterThreeAttempts() {
        wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.fetchCurrentDriverStandings())
                .isInstanceOf(RestClientException.class);

        wireMock.verify(3, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/current/driverStandings/")));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=JolpicaClientRetryTest`
Expected: FAIL — `recoversAfterTwoServerErrors` throws `RestClientException` on the first 503, and `givesUpAfterThreeAttempts` records 1 request rather than 3, because no retry is configured yet.

- [ ] **Step 4: Annotate the client**

In `JolpicaClient.java`, add the import and annotation:

```java
import io.github.resilience4j.retry.annotation.Retry;
```

and change the method signature to:

```java
    @Retry(name = "jolpica")
    public StandingsEnvelope fetchCurrentDriverStandings() {
```

Also extend the Javadoc on the method:

```java
    /**
     * @return the current season's driver standings exactly as Jolpica sends them
     * @throws org.springframework.web.client.RestClientException after retries
     *         are exhausted. Resilience4j rethrows the original exception rather
     *         than a wrapper, so the service translates it.
     */
```

- [ ] **Step 5: Configure retry in application.yml**

Add as a new top-level block:

```yaml
resilience4j:
  retry:
    instances:
      jolpica:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        # Only transport and server-side failures are worth retrying. A 4xx means
        # our request is wrong, and repeating it just adds load to a
        # volunteer-run API (PRD 13).
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

- [ ] **Step 6: Run the retry test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=JolpicaClientRetryTest`
Expected: PASS, 2 tests. Both assert exactly 3 upstream requests.

- [ ] **Step 7: Create UpstreamUnavailableException**

```java
package com.f1dashboard.api.common;

/**
 * Thrown when Jolpica could not be reached or returned something unusable, after
 * retries were exhausted.
 *
 * <p>Distinct from a generic failure on purpose: this is a dependency being down,
 * not a bug in this application, and the difference is worth 503 rather than 500
 * so the frontend can tell "we are broken" from "our data source is unreachable".
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 8: Write the failing 503 contract test**

```java
package com.f1dashboard.api.common;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.f1dashboard.api.jolpica.JolpicaClient;

/**
 * An upstream outage must not be reported as 500. The frontend branches on this:
 * 503 means "the data source is down, try later", 500 would mean "this
 * application is broken".
 */
@SpringBootTest
@AutoConfigureMockMvc
class UpstreamUnavailableIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JolpicaClient jolpicaClient;

    @Test
    void upstreamFailureIsReportedAs503NotInternalError() throws Exception {
        when(jolpicaClient.fetchCurrentDriverStandings())
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));

        mockMvc.perform(get("/api/standings/drivers"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").value("/api/standings/drivers"))
                .andExpect(jsonPath("$.timestamp").exists())
                // The upstream's own message must never reach the client: it can
                // name a host, a port or a credential.
                .andExpect(jsonPath("$.message").value("F1 data is temporarily unavailable"));
    }
}
```

**Note:** this test requires the controller from Task 6. It will not pass until then. Add it now, run it to watch it fail for the right reason, and mark it `@org.junit.jupiter.api.Disabled("enabled in Task 6")` so the suite stays green in between. Task 6 removes the annotation.

- [ ] **Step 9: Add the handler method**

In `GlobalExceptionHandler.java`, add after `handleResourceNotFound`:

```java
    /**
     * Upstream is down, not us. Logged at warn rather than error: it is not a
     * defect in this application and should not read like one in the logs.
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ApiError> handleUpstreamUnavailable(UpstreamUnavailableException ex,
                                                       HttpServletRequest request) {
        log.warn("Upstream unavailable serving {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                "F1 data is temporarily unavailable", request);
    }
```

- [ ] **Step 10: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 45 tests, 1 skipped (the disabled 503 test).

- [ ] **Step 11: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
        backend/src/main/java/com/f1dashboard/api/jolpica/JolpicaClient.java \
        backend/src/main/java/com/f1dashboard/api/common/UpstreamUnavailableException.java \
        backend/src/main/java/com/f1dashboard/api/common/GlobalExceptionHandler.java \
        backend/src/test/java/com/f1dashboard/api/jolpica/JolpicaClientRetryTest.java \
        backend/src/test/java/com/f1dashboard/api/common/UpstreamUnavailableIntegrationTest.java
git commit -m "feat(resilience): retry Jolpica with backoff, report outages as 503

Uses resilience4j-spring-boot3 2.3.0 — PRD 6.3 names resilience4j-spring-boot4
2.4.0, which does not exist on Maven Central. The Boot 3 starter was verified
working on Boot 4.1.0/Java 25 by spike. Needs spring-boot-starter-aspectj;
spring-boot-starter-aop has no Boot 4 release.

Also fixes a scaffold gap: GlobalExceptionHandler mapped any unrecognised
exception to 500, so a Jolpica outage looked like an internal defect."
```

---

## Task 5: DriverStandingsMapper

**Files:**
- Create: `backend/src/main/java/com/f1dashboard/api/standings/DriverStandingRow.java`
- Create: `backend/src/main/java/com/f1dashboard/api/mapping/DriverStandingsMapper.java`
- Test: `backend/src/test/java/com/f1dashboard/api/mapping/DriverStandingsMapperTest.java`

**Interfaces:**
- Consumes: `JolpicaDriverStanding` (Task 1), `NationalityMapper.toIsoCode(String)` (Task 2).
- Produces: `DriverStandingRow`, a record with components `int position`, `String driverId`, `String code`, `Integer permanentNumber`, `String givenName`, `String familyName`, `String nationality`, `String countryCode`, `String constructorId`, `String constructorName`, `BigDecimal points`, `int wins`. And `DriverStandingsMapper.toRows(List<JolpicaDriverStanding>)` → `List<DriverStandingRow>`, returning an empty list for null or empty input.

- [ ] **Step 1: Write the failing test**

```java
package com.f1dashboard.api.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.f1dashboard.api.jolpica.dto.JolpicaDriverStanding;
import com.f1dashboard.api.jolpica.dto.StandingsEnvelope;
import com.f1dashboard.api.standings.DriverStandingRow;

/**
 * Every business rule in this slice lives here, so this is the densest test in
 * the feature. Input comes from the captured fixture rather than hand-built
 * objects, so the rules are proven against the shape upstream really sends.
 */
class DriverStandingsMapperTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final DriverStandingsMapper mapper = new DriverStandingsMapper(new NationalityMapper());

    private List<JolpicaDriverStanding> fixtureRows() throws IOException {
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/jolpica/driver-standings-2026-r12.json")) {
            assertThat(in).isNotNull();
            return json.readValue(in, StandingsEnvelope.class)
                    .mrData().standingsTable().standingsLists().getFirst().driverStandings();
        }
    }

    @Test
    void mapsEveryFieldOfALeaderRow() throws IOException {
        DriverStandingRow leader = mapper.toRows(fixtureRows()).getFirst();

        assertThat(leader.position()).isEqualTo(1);
        assertThat(leader.driverId()).isEqualTo("antonelli");
        assertThat(leader.code()).isEqualTo("ANT");
        assertThat(leader.permanentNumber()).isEqualTo(12);
        assertThat(leader.givenName()).isEqualTo("Andrea Kimi");
        assertThat(leader.familyName()).isEqualTo("Antonelli");
        assertThat(leader.nationality()).isEqualTo("Italian");
        assertThat(leader.countryCode()).isEqualTo("IT");
        assertThat(leader.constructorId()).isEqualTo("mercedes");
        assertThat(leader.constructorName()).isEqualTo("Mercedes");
        assertThat(leader.points()).isEqualByComparingTo("242");
        assertThat(leader.wins()).isEqualTo(6);
    }

    @Test
    void usesTheLastConstructorForADriverWhoSwitchedTeams() throws IOException {
        DriverStandingRow lawson = mapper.toRows(fixtureRows()).get(1);

        assertThat(lawson.driverId()).isEqualTo("lawson");
        // Ergast orders the array with the current team last. Asserted against
        // lawson's real payload so an upstream reordering fails here rather than
        // mislabelling a driver in production.
        assertThat(lawson.constructorId()).isEqualTo("red_bull");
        assertThat(lawson.constructorName()).isEqualTo("Red Bull");
    }

    @Test
    void preservesFractionalPoints() throws IOException {
        DriverStandingRow halfPoints = mapper.toRows(fixtureRows()).get(2);

        // Half points are awarded when a race is abandoned early; 2021 returns
        // "395.5". Parsing as int would throw.
        assertThat(halfPoints.points()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void leavesCountryCodeNullForAnUnmappedNationality() throws IOException {
        DriverStandingRow unmapped = mapper.toRows(fixtureRows()).get(2);

        assertThat(unmapped.nationality()).isEqualTo("Atlantean");
        assertThat(unmapped.countryCode()).isNull();
    }

    @Test
    void mapsOffSeasonToAnEmptyList() {
        assertThat(mapper.toRows(List.of())).isEmpty();
    }

    @Test
    void mapsNullInputToAnEmptyList() {
        // StandingsLists is [] off-season, so navigating to driverStandings can
        // legitimately yield null. An empty table is correct; a crash is not.
        assertThat(mapper.toRows(null)).isEmpty();
    }

    @Test
    void preservesUpstreamOrdering() throws IOException {
        assertThat(mapper.toRows(fixtureRows()))
                .extracting(DriverStandingRow::position)
                .containsExactly(1, 9, 20);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=DriverStandingsMapperTest`
Expected: FAIL — compilation error, `DriverStandingRow` and `DriverStandingsMapper` do not exist.

- [ ] **Step 3: Create DriverStandingRow**

```java
package com.f1dashboard.api.standings;

import java.math.BigDecimal;

/**
 * One row of the driver standings as this API exposes it (PRD 4.1).
 *
 * <p>Podiums and DNFs are absent deliberately: Jolpica's standings response
 * carries only points and wins, and deriving the other two requires the
 * full-season results pipeline that F8 builds. PRD 4.1 is amended accordingly.
 *
 * @param permanentNumber the driver's career number. Boxed because a driver
 *                        without one is possible upstream even though every
 *                        current driver has one.
 * @param countryCode     ISO 3166-1 alpha-2, or {@code null} when the nationality
 *                        demonym is unmapped. The frontend then omits the flag.
 * @param constructorName the driver's current team — the last entry in Jolpica's
 *                        ordered Constructors array.
 * @param points          BigDecimal, not int: half points are real.
 */
public record DriverStandingRow(
        int position,
        String driverId,
        String code,
        Integer permanentNumber,
        String givenName,
        String familyName,
        String nationality,
        String countryCode,
        String constructorId,
        String constructorName,
        BigDecimal points,
        int wins) {
}
```

- [ ] **Step 4: Create DriverStandingsMapper**

```java
package com.f1dashboard.api.mapping;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.f1dashboard.api.jolpica.dto.JolpicaConstructor;
import com.f1dashboard.api.jolpica.dto.JolpicaDriver;
import com.f1dashboard.api.jolpica.dto.JolpicaDriverStanding;
import com.f1dashboard.api.standings.DriverStandingRow;

/**
 * Turns Jolpica's standings rows into this API's rows.
 *
 * <p>All numeric conversion happens here rather than in the DTOs, because
 * upstream quotes every number and one conversion site means one place for a
 * malformed value to fail.
 */
@Component
public class DriverStandingsMapper {

    private final NationalityMapper nationalityMapper;

    DriverStandingsMapper(NationalityMapper nationalityMapper) {
        this.nationalityMapper = nationalityMapper;
    }

    /**
     * @param standings upstream rows, or {@code null} off-season
     * @return rows in upstream order; empty when there is nothing to map
     */
    public List<DriverStandingRow> toRows(List<JolpicaDriverStanding> standings) {
        if (standings == null || standings.isEmpty()) {
            return List.of();
        }
        return standings.stream().map(this::toRow).toList();
    }

    private DriverStandingRow toRow(JolpicaDriverStanding standing) {
        JolpicaDriver driver = standing.driver();
        JolpicaConstructor constructor = currentConstructor(standing);

        return new DriverStandingRow(
                Integer.parseInt(standing.position()),
                driver.driverId(),
                driver.code(),
                driver.permanentNumber() == null ? null : Integer.valueOf(driver.permanentNumber()),
                driver.givenName(),
                driver.familyName(),
                driver.nationality(),
                nationalityMapper.toIsoCode(driver.nationality()),
                constructor == null ? null : constructor.constructorId(),
                constructor == null ? null : constructor.name(),
                new BigDecimal(standing.points()),
                Integer.parseInt(standing.wins()));
    }

    /**
     * Ergast orders Constructors with the driver's current team last, so a
     * mid-season switch leaves the former team at index 0. Taking the first
     * entry would show the wrong team for exactly the drivers people are most
     * curious about.
     */
    private JolpicaConstructor currentConstructor(JolpicaDriverStanding standing) {
        List<JolpicaConstructor> constructors = standing.constructors();
        return constructors == null || constructors.isEmpty() ? null : constructors.getLast();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=DriverStandingsMapperTest`
Expected: PASS, 7 tests.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 52 tests, 1 skipped.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/f1dashboard/api/standings/DriverStandingRow.java \
        backend/src/main/java/com/f1dashboard/api/mapping/DriverStandingsMapper.java \
        backend/src/test/java/com/f1dashboard/api/mapping/DriverStandingsMapperTest.java
git commit -m "feat(standings): map Jolpica standings rows to the API DTO

Three rules worth naming: the driver's current team is the LAST entry in
Jolpica's Constructors array (asserted against lawson's real two-team payload);
points are BigDecimal because half points are real; an unmapped nationality
yields a null country code rather than an error."
```

---

## Task 6: Service, controller and endpoint

**Files:**
- Create: `backend/src/main/java/com/f1dashboard/api/standings/DriverStandingsService.java`
- Create: `backend/src/main/java/com/f1dashboard/api/standings/DriverStandingsController.java`
- Modify: `backend/src/test/java/com/f1dashboard/api/common/UpstreamUnavailableIntegrationTest.java` (remove `@Disabled`)
- Test: `backend/src/test/java/com/f1dashboard/api/standings/DriverStandingsControllerTest.java`

**Interfaces:**
- Consumes: `JolpicaClient.fetchCurrentDriverStandings()`, `DriverStandingsMapper.toRows(...)`, `ApiResponse.live(data, season, round, fetchedAt)`, `UpstreamUnavailableException`.
- Produces: `GET /api/standings/drivers` returning `ApiResponse<List<DriverStandingRow>>`. `DriverStandingsService.currentDriverStandings()` returns the same type.

- [ ] **Step 1: Write the failing controller test**

```java
package com.f1dashboard.api.standings;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The full slice end to end: HTTP in, stubbed Jolpica out, envelope back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriverStandingsControllerTest {

    private static WireMockServer wireMock;

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void jolpicaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jolpica.base-url", () -> wireMock.baseUrl());
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private static void stubJolpicaWith(String fixture) throws IOException {
        try (InputStream in = DriverStandingsControllerTest.class
                .getResourceAsStream("/fixtures/jolpica/" + fixture)) {
            assertThat(in).isNotNull();
            wireMock.stubFor(get(urlPathEqualTo("/current/driverStandings/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(new String(in.readAllBytes()))));
        }
    }

    @Test
    void returnsRowsAndProvenanceForTheCurrentSeason() throws Exception {
        stubJolpicaWith("driver-standings-2026-r12.json");

        mockMvc.perform(get("/api/standings/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data[0].driverId").value("antonelli"))
                .andExpect(jsonPath("$.data[0].countryCode").value("IT"))
                .andExpect(jsonPath("$.data[0].points").value(242))
                .andExpect(jsonPath("$.data[0].wins").value(6))
                // Current team, not the one lawson left.
                .andExpect(jsonPath("$.data[1].constructorName").value("Red Bull"))
                // Season and round come from StandingsTable, never the calendar.
                .andExpect(jsonPath("$.meta.season").value(2026))
                .andExpect(jsonPath("$.meta.round").value(12))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.fetchedAt").exists());
    }

    @Test
    void returnsAnEmptyTableWithNullRoundOffSeason() throws Exception {
        stubJolpicaWith("driver-standings-offseason.json");

        mockMvc.perform(get("/api/standings/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.meta.season").value(2027))
                // Off-season is a 200 with no round, not an error.
                .andExpect(jsonPath("$.meta.round").doesNotExist())
                .andExpect(jsonPath("$.meta.source").value("LIVE"));
    }

    @Test
    void serialisesFractionalPointsAsANumber() throws Exception {
        stubJolpicaWith("driver-standings-2026-r12.json");

        mockMvc.perform(get("/api/standings/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].points").value(0.5));
    }

    @Test
    void omitsCountryCodeForAnUnmappedNationality() throws Exception {
        stubJolpicaWith("driver-standings-2026-r12.json");

        mockMvc.perform(get("/api/standings/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].nationality").value("Atlantean"))
                .andExpect(jsonPath("$.data[2].countryCode").value((Object) null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -B test -Dtest=DriverStandingsControllerTest`
Expected: FAIL — all four tests 404, because no controller is mapped to `/api/standings/drivers`.

- [ ] **Step 3: Create DriverStandingsService**

```java
package com.f1dashboard.api.standings;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;

import com.f1dashboard.api.common.ApiResponse;
import com.f1dashboard.api.common.UpstreamUnavailableException;
import com.f1dashboard.api.jolpica.JolpicaClient;
import com.f1dashboard.api.jolpica.dto.JolpicaStandingsList;
import com.f1dashboard.api.jolpica.dto.JolpicaStandingsTable;
import com.f1dashboard.api.mapping.DriverStandingsMapper;

/**
 * Orchestrates the driver standings read: fetch, navigate the upstream envelope,
 * map, and attach provenance.
 *
 * <p>Error translation lives here rather than in {@link JolpicaClient} because
 * the client's fetch method is proxied by {@code @Retry} — catching inside it
 * would hide failures from the retry entirely.
 */
@Service
public class DriverStandingsService {

    private final JolpicaClient jolpicaClient;
    private final DriverStandingsMapper mapper;

    DriverStandingsService(JolpicaClient jolpicaClient, DriverStandingsMapper mapper) {
        this.jolpicaClient = jolpicaClient;
        this.mapper = mapper;
    }

    public ApiResponse<List<DriverStandingRow>> currentDriverStandings() {
        JolpicaStandingsTable table = fetchTable();

        List<DriverStandingRow> rows = mapper.toRows(
                table.standingsLists().isEmpty()
                        ? null
                        : table.standingsLists().getFirst().driverStandings());

        return ApiResponse.live(rows, parseOrNull(table.season()), roundOf(table), Instant.now());
    }

    private JolpicaStandingsTable fetchTable() {
        try {
            return jolpicaClient.fetchCurrentDriverStandings().mrData().standingsTable();
        } catch (RestClientException | JacksonException ex) {
            // JacksonException is unchecked in Jackson 3, so an unusable body
            // would otherwise escape as a 500.
            throw new UpstreamUnavailableException("Could not read driver standings from Jolpica", ex);
        }
    }

    /**
     * Round comes from the list rather than the table when present, because the
     * table's round is null off-season while the list is the authority when data
     * exists. Both are read back out of the response rather than assumed.
     */
    private Integer roundOf(JolpicaStandingsTable table) {
        if (table.standingsLists().isEmpty()) {
            return parseOrNull(table.round());
        }
        JolpicaStandingsList list = table.standingsLists().getFirst();
        return parseOrNull(list.round() != null ? list.round() : table.round());
    }

    private Integer parseOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
```

- [ ] **Step 4: Create DriverStandingsController**

```java
package com.f1dashboard.api.standings;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.f1dashboard.api.common.ApiResponse;

/**
 * Current-season driver standings (PRD 8).
 *
 * <p>Thin on purpose: every decision lives in the service or the mapper, which
 * are testable without a servlet.
 */
@RestController
@RequestMapping("/api/standings/drivers")
public class DriverStandingsController {

    private final DriverStandingsService service;

    DriverStandingsController(DriverStandingsService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<DriverStandingRow>> get() {
        return service.currentDriverStandings();
    }
}
```

- [ ] **Step 5: Run the controller test to verify it passes**

Run: `cd backend && ./mvnw -B test -Dtest=DriverStandingsControllerTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Enable the 503 test from Task 4**

Remove the `@Disabled("enabled in Task 6")` annotation and its import from `UpstreamUnavailableIntegrationTest.java`.

- [ ] **Step 7: Run it**

Run: `cd backend && ./mvnw -B test -Dtest=UpstreamUnavailableIntegrationTest`
Expected: PASS, 1 test — 503 with code `UPSTREAM_UNAVAILABLE`.

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./mvnw -B verify`
Expected: BUILD SUCCESS, 57 tests, 0 skipped.

- [ ] **Step 9: Verify against the real API by hand**

```bash
cd backend && ./mvnw spring-boot:run
```

In another shell:

```bash
curl -s http://localhost:8080/api/standings/drivers | head -40
```

Expected: 23 rows of real 2026 data, `meta.season` 2026, `meta.source` `"LIVE"`. This is the first time the slice touches the live API — the fixtures prove shape, this proves reality.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/f1dashboard/api/standings/DriverStandingsService.java \
        backend/src/main/java/com/f1dashboard/api/standings/DriverStandingsController.java \
        backend/src/test/java/com/f1dashboard/api/standings/DriverStandingsControllerTest.java \
        backend/src/test/java/com/f1dashboard/api/common/UpstreamUnavailableIntegrationTest.java
git commit -m "feat(standings): expose GET /api/standings/drivers

Season and round are read back out of StandingsTable rather than inferred from
the calendar, so a season boundary cannot mislabel data. Off-season is a 200
with an empty table and a null round, not an error.

Error translation sits in the service, not the client: the client's fetch is
proxied by @Retry, so catching inside it would hide failures from the retry."
```

---

## Task 7: RTK Query endpoint and types

**Files:**
- Modify: `frontend/src/store/api.ts`
- Create: `frontend/src/test/fetch.ts`
- Modify: `frontend/src/features/health/api-status.test.tsx`

**Interfaces:**
- Consumes: the backend contract from Task 6.
- Produces: `DriverStandingRow` type; `useDriverStandingsQuery()` returning `ApiResponse<DriverStandingRow[]>`. `stubFetchJson(body, init?)` and `stubFetchPending()` and `stubFetchError()` from `@/test/fetch`.

- [ ] **Step 1: Add the type and endpoint**

In `frontend/src/store/api.ts`, add after the `HealthStatus` type:

```ts
/**
 * One row of the driver standings, mirroring the backend DTO.
 *
 * `countryCode` is null when the backend could not map the nationality demonym
 * to an ISO code — the flag is then omitted and the nationality text carries the
 * meaning on its own.
 *
 * Podiums and DNFs are absent until F8, which builds the results pipeline they
 * are derived from.
 */
export type DriverStandingRow = {
  position: number
  driverId: string
  code: string
  permanentNumber: number | null
  givenName: string
  familyName: string
  nationality: string
  countryCode: string | null
  constructorId: string
  constructorName: string
  points: number
  wins: number
}
```

Change `tagTypes` to include the new tag:

```ts
  tagTypes: ['Health', 'DriverStandings'],
```

Add the endpoint inside `endpoints`:

```ts
    driverStandings: build.query<ApiResponse<DriverStandingRow[]>, void>({
      query: () => '/standings/drivers',
      providesTags: ['DriverStandings'],
    }),
```

And extend the export:

```ts
export const { useHealthQuery, useDriverStandingsQuery } = f1Api
```

The query type is the whole `ApiResponse`, not just the rows: `meta` carries the provenance that drives the "Last updated" banner F2 adds, so discarding it here would mean re-plumbing then.

- [ ] **Step 2: Extract the shared fetch helper**

Create `frontend/src/test/fetch.ts`:

```ts
import { vi } from 'vitest'

/**
 * Fetch stubs shared across feature tests.
 *
 * Deliberately not MSW: there are two endpoints, and a mock-service layer would
 * add a dependency to a repo whose audit surface we are actively shrinking.
 * Revisit if the endpoint count or the response-shape variety grows.
 */
export function stubFetch() {
  vi.stubGlobal('fetch', vi.fn())
}

export function stubFetchJson(body: unknown, status = 200) {
  vi.mocked(fetch).mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
}

/** Never resolves — leaves the query in its loading state. */
export function stubFetchPending() {
  vi.mocked(fetch).mockReturnValue(new Promise(() => {}))
}

export function stubFetchError() {
  vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))
}
```

- [ ] **Step 3: Point the existing health test at the helper**

Rewrite `frontend/src/features/health/api-status.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiStatus } from '@/features/health/api-status'
import { stubFetch, stubFetchError, stubFetchJson, stubFetchPending } from '@/test/fetch'
import { renderWithProviders } from '@/test/render'

/** Covers the three states PRD 9 requires. */
describe('ApiStatus', () => {
  beforeEach(() => {
    stubFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows a skeleton while the request is in flight', () => {
    stubFetchPending()
    renderWithProviders(<ApiStatus />)

    expect(screen.getByText('Checking backend status')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
  })

  it('shows status and version once loaded', async () => {
    stubFetchJson({ status: 'UP', version: '0.0.1-SNAPSHOT', timestamp: '2026-07-25T09:12:03Z' })
    renderWithProviders(<ApiStatus />)

    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(screen.getByText('0.0.1-SNAPSHOT')).toBeInTheDocument()
  })

  it('shows an error state when the API cannot be reached', async () => {
    stubFetchError()
    renderWithProviders(<ApiStatus />)

    await waitFor(() => {
      expect(screen.getByText('API unreachable')).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 4: Verify**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: all pass, 8 tests still green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/store/api.ts frontend/src/test/fetch.ts \
        frontend/src/features/health/api-status.test.tsx
git commit -m "feat(web): add the driver standings query and share fetch stubs

The query returns the whole {data, meta} envelope rather than just the rows:
meta carries the provenance that drives F2's 'Last updated' banner, so
discarding it here would mean re-plumbing later."
```

---

## Task 8: Flag component

**Files:**
- Create: `frontend/public/flags/{ar,au,br,gb,ca,nl,fi,fr,de,it,jp,mx,mc,nz,es,th}.svg` (16 files)
- Create: `frontend/src/features/standings/flag.tsx`
- Test: `frontend/src/features/standings/flag.test.tsx`

**Interfaces:**
- Consumes: `DriverStandingRow['countryCode']`.
- Produces: `<Flag countryCode={string | null} />` — renders an `<img>` with `src="/flags/<lowercase-iso>.svg"`, empty `alt`, and `aria-hidden="true"`; renders `null` when `countryCode` is null.

- [ ] **Step 1: Vendor the 16 flag SVGs**

Source them from a public-domain / CC0 set (for example the `flag-icons` project's `flags/4x3` directory, which is MIT-licensed). Copy exactly these 16 into `frontend/public/flags/`, named by lowercase ISO code:

`ar.svg au.svg br.svg ca.svg de.svg es.svg fi.svg fr.svg gb.svg it.svg jp.svg mc.svg mx.svg nl.svg nz.svg th.svg`

Record the source and licence in `docs/decisions.md` at Task 11.

Verify all 16 landed:

```bash
cd frontend && ls public/flags/*.svg | wc -l
```
Expected: `16`

- [ ] **Step 2: Write the component**

```tsx
/**
 * Nationality flag. Purely decorative: the nationality text next to it carries
 * the accessible meaning, so this is hidden from assistive tech entirely.
 *
 * Local SVG rather than an emoji flag — emoji render as two boxed letters on
 * Windows, and PRD 5 commits to supporting Edge.
 *
 * Served from /flags rather than imported from src/assets because the filename
 * is computed from the backend's ISO code at runtime, and Vite's asset imports
 * need static specifiers.
 */
export function Flag({ countryCode }: { countryCode: string | null }) {
  if (!countryCode) {
    return null
  }

  return (
    <img
      src={`/flags/${countryCode.toLowerCase()}.svg`}
      alt=""
      aria-hidden="true"
      width={20}
      height={15}
      loading="lazy"
      className="inline-block h-[15px] w-[20px] shrink-0 rounded-sm object-cover"
    />
  )
}
```

- [ ] **Step 3: Write the test**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Flag } from '@/features/standings/flag'

describe('Flag', () => {
  it('renders the SVG for a known country code', () => {
    render(<Flag countryCode="IT" />)

    const img = screen.getByRole('presentation', { hidden: true })
    expect(img).toHaveAttribute('src', '/flags/it.svg')
  })

  it('lowercases the code to match the vendored filenames', () => {
    render(<Flag countryCode="NZ" />)

    expect(screen.getByRole('presentation', { hidden: true })).toHaveAttribute(
      'src',
      '/flags/nz.svg',
    )
  })

  it('renders nothing when the backend could not map the nationality', () => {
    const { container } = render(<Flag countryCode={null} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('is hidden from assistive tech, since nationality text carries the meaning', () => {
    render(<Flag countryCode="GB" />)

    const img = screen.getByRole('presentation', { hidden: true })
    expect(img).toHaveAttribute('aria-hidden', 'true')
    expect(img).toHaveAttribute('alt', '')
  })
})
```

- [ ] **Step 4: Verify**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: all pass, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/public/flags frontend/src/features/standings/flag.tsx \
        frontend/src/features/standings/flag.test.tsx
git commit -m "feat(web): decorative nationality flags from local SVGs

Local SVG rather than emoji: emoji flags render as two boxed letters on
Windows, and PRD 5 commits to supporting Edge. Hidden from assistive tech
because the nationality text beside it already carries the meaning.

Renders nothing when countryCode is null, so an unmapped demonym costs one
row its decoration rather than breaking the table."
```

---

## Task 9: Table and cards

**Files:**
- Create: `frontend/src/features/standings/standings-table.tsx`
- Create: `frontend/src/features/standings/standings-cards.tsx`
- Test: `frontend/src/features/standings/standings-presentations.test.tsx`

**Interfaces:**
- Consumes: `DriverStandingRow` (Task 7), `<Flag />` (Task 8).
- Produces: `<StandingsTable rows={DriverStandingRow[]} />` and `<StandingsCards rows={DriverStandingRow[]} />`. Both accept the identical prop and render the same data. Neither owns loading, empty or error state — that is Task 10's job.

- [ ] **Step 1: Write the table**

```tsx
import { Flag } from '@/features/standings/flag'
import type { DriverStandingRow } from '@/store/api'

/**
 * Desktop presentation. A real <table> with a caption and column scopes, so
 * screen readers get genuine row/column semantics.
 *
 * Hidden below `md`, where StandingsCards takes over. The two are separate DOM
 * trees rather than one table restyled by CSS: restyling a table with `display`
 * swaps strips exactly the semantics this element exists to provide.
 */
export function StandingsTable({ rows }: { rows: DriverStandingRow[] }) {
  return (
    <div className="hidden md:block">
      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">
          Formula 1 driver championship standings for the current season
        </caption>
        <thead>
          <tr className="border-b border-border text-left">
            <th scope="col" className="w-10 py-2 pr-2 font-medium text-muted-foreground">
              Pos
            </th>
            <th scope="col" className="py-2 pr-2 font-medium text-muted-foreground">
              Driver
            </th>
            <th scope="col" className="py-2 pr-2 font-medium text-muted-foreground">
              Constructor
            </th>
            <th scope="col" className="py-2 pr-2 text-right font-medium text-muted-foreground">
              Points
            </th>
            <th scope="col" className="py-2 text-right font-medium text-muted-foreground">
              Wins
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.driverId} className="border-b border-border/60">
              <th scope="row" className="py-2 pr-2 font-normal text-muted-foreground tabular-nums">
                {row.position}
              </th>
              <td className="py-2 pr-2">
                <span className="flex items-center gap-2">
                  <Flag countryCode={row.countryCode} />
                  <span className="font-medium">
                    {row.givenName} {row.familyName}
                  </span>
                  <span className="sr-only">, {row.nationality}</span>
                </span>
              </td>
              <td className="py-2 pr-2 text-muted-foreground">{row.constructorName}</td>
              <td className="py-2 pr-2 text-right font-semibold tabular-nums">{row.points}</td>
              <td className="py-2 text-right tabular-nums">{row.wins}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 2: Write the cards**

```tsx
import { Flag } from '@/features/standings/flag'
import type { DriverStandingRow } from '@/store/api'

/**
 * Mobile presentation, shown below `md`. A list rather than a scrollable table:
 * horizontal scrolling on a phone is the interaction users most often miss, and
 * hiding the Wins column would drop data outright.
 *
 * Full driver names fit here, which they do not in a 360px-wide table.
 */
export function StandingsCards({ rows }: { rows: DriverStandingRow[] }) {
  return (
    <ul aria-label="Driver championship standings" className="md:hidden">
      {rows.map((row) => (
        <li
          key={row.driverId}
          className="flex items-center gap-3 border-b border-border/60 py-3"
        >
          <span className="w-6 shrink-0 text-right font-semibold tabular-nums text-muted-foreground">
            {row.position}
          </span>

          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2">
              <Flag countryCode={row.countryCode} />
              <span className="truncate font-medium">
                {row.givenName} {row.familyName}
              </span>
            </span>
            <span className="block text-xs text-muted-foreground">
              {row.constructorName} · {row.nationality}
            </span>
          </span>

          <span className="shrink-0 text-right">
            <span className="block font-semibold tabular-nums">{row.points}</span>
            <span className="block text-xs text-muted-foreground">
              {row.wins} {row.wins === 1 ? 'win' : 'wins'}
            </span>
          </span>
        </li>
      ))}
    </ul>
  )
}
```

- [ ] **Step 3: Write the test**

```tsx
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { StandingsCards } from '@/features/standings/standings-cards'
import { StandingsTable } from '@/features/standings/standings-table'
import type { DriverStandingRow } from '@/store/api'

const ROWS: DriverStandingRow[] = [
  {
    position: 1,
    driverId: 'antonelli',
    code: 'ANT',
    permanentNumber: 12,
    givenName: 'Andrea Kimi',
    familyName: 'Antonelli',
    nationality: 'Italian',
    countryCode: 'IT',
    constructorId: 'mercedes',
    constructorName: 'Mercedes',
    points: 242,
    wins: 6,
  },
  {
    position: 9,
    driverId: 'lawson',
    code: 'LAW',
    permanentNumber: 30,
    givenName: 'Liam',
    familyName: 'Lawson',
    nationality: 'New Zealander',
    countryCode: 'NZ',
    constructorId: 'red_bull',
    constructorName: 'Red Bull',
    points: 49,
    wins: 1,
  },
]

/**
 * Both trees exist in jsdom regardless of viewport — the split is CSS-only. So
 * every query here is scoped to the tree under test; an unscoped
 * getByRole('table') would silently pass while testing the wrong element.
 */
describe('StandingsTable', () => {
  it('renders one row per driver with real table semantics', () => {
    render(<StandingsTable rows={ROWS} />)

    const table = screen.getByRole('table')
    // Header row plus one row per driver.
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(within(table).getByRole('columnheader', { name: 'Points' })).toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: '1' })).toBeInTheDocument()
  })

  it('has an accessible caption', () => {
    render(<StandingsTable rows={ROWS} />)

    expect(screen.getByRole('table')).toHaveAccessibleName(
      'Formula 1 driver championship standings for the current season',
    )
  })

  it('shows points, wins and the current constructor', () => {
    render(<StandingsTable rows={ROWS} />)
    const table = screen.getByRole('table')

    expect(within(table).getByText('242')).toBeInTheDocument()
    expect(within(table).getByText('Red Bull')).toBeInTheDocument()
  })

  it('keeps nationality available to screen readers even though the flag is decorative', () => {
    render(<StandingsTable rows={ROWS} />)

    expect(screen.getByRole('table')).toHaveTextContent('Italian')
  })
})

describe('StandingsCards', () => {
  it('renders one list item per driver', () => {
    render(<StandingsCards rows={ROWS} />)

    const list = screen.getByRole('list', { name: 'Driver championship standings' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
  })

  it('shows the full driver name, which does not fit in the mobile table', () => {
    render(<StandingsCards rows={ROWS} />)
    const list = screen.getByRole('list', { name: 'Driver championship standings' })

    expect(within(list).getByText('Andrea Kimi Antonelli')).toBeInTheDocument()
  })

  it('hides no data — points and wins are both present', () => {
    render(<StandingsCards rows={ROWS} />)
    const list = screen.getByRole('list', { name: 'Driver championship standings' })

    expect(within(list).getByText('242')).toBeInTheDocument()
    expect(within(list).getByText('6 wins')).toBeInTheDocument()
  })

  it('singularises a one-win tally', () => {
    render(<StandingsCards rows={ROWS} />)
    const list = screen.getByRole('list', { name: 'Driver championship standings' })

    expect(within(list).getByText('1 win')).toBeInTheDocument()
  })

  it('renders the same drivers as the table', () => {
    const { unmount } = render(<StandingsTable rows={ROWS} />)
    const inTable = screen.getByRole('table').textContent
    unmount()

    render(<StandingsCards rows={ROWS} />)
    const inCards = screen.getByRole('list', { name: 'Driver championship standings' }).textContent

    for (const name of ['Antonelli', 'Lawson', 'Mercedes', 'Red Bull']) {
      expect(inTable).toContain(name)
      expect(inCards).toContain(name)
    }
  })
})
```

- [ ] **Step 4: Verify**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: all pass, 21 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/standings/standings-table.tsx \
        frontend/src/features/standings/standings-cards.tsx \
        frontend/src/features/standings/standings-presentations.test.tsx
git commit -m "feat(web): table and card presentations for driver standings

Two sibling DOM trees from one data shape, switched by CSS. Deliberately not
one table restyled with display swaps, which strips the row and column
semantics the table exists to provide.

Tests scope every query to the tree under test: both trees are present in
jsdom regardless of viewport, so an unscoped getByRole would pass while
asserting against the wrong element."
```

---

## Task 10: The view and route

**Files:**
- Create: `frontend/src/features/standings/driver-standings.tsx`
- Test: `frontend/src/features/standings/driver-standings.test.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `useDriverStandingsQuery()` (Task 7), `<StandingsTable />` and `<StandingsCards />` (Task 9).
- Produces: `<DriverStandings />`, mounted at `/standings/drivers`, with `/` redirecting to it.

- [ ] **Step 1: Write the view**

```tsx
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { StandingsCards } from '@/features/standings/standings-cards'
import { StandingsTable } from '@/features/standings/standings-table'
import { useDriverStandingsQuery } from '@/store/api'

/**
 * Owns the three states PRD 9 requires, so the presentation components stay
 * pure functions of their rows.
 *
 * There is no "stale" branch yet: with no cache tier, nothing can set
 * meta.stale, so the "Last updated" banner PRD 4.7 asks for arrives with F2.
 */
export function DriverStandings() {
  const { data, isLoading, isError } = useDriverStandingsQuery()

  const rows = data?.data ?? []
  const season = data?.meta.season

  return (
    <Card>
      <CardHeader>
        <CardTitle>Driver standings</CardTitle>
        <CardDescription>
          {season ? `Formula 1 ${season} championship` : 'Formula 1 championship'}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div role="status" aria-busy="true" className="space-y-3">
            <span className="sr-only">Loading driver standings</span>
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : isError ? (
          <Alert variant="destructive">
            <AlertTitle>Standings unavailable</AlertTitle>
            <AlertDescription>
              We could not reach the F1 data source. This is usually temporary — try again in a
              few minutes.
            </AlertDescription>
          </Alert>
        ) : rows.length === 0 ? (
          // PRD 4.1: an explicit off-season empty state, never a blank table.
          <div className="py-8 text-center">
            <p className="font-medium">The season hasn&apos;t started yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {season
                ? `No results have been recorded for the ${season} season. Standings appear here after the first race.`
                : 'Standings will appear here after the first race of the season.'}
            </p>
          </div>
        ) : (
          <>
            <StandingsTable rows={rows} />
            <StandingsCards rows={rows} />
          </>
        )}
      </CardContent>
    </Card>
  )
}
```

- [ ] **Step 2: Write the test**

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DriverStandings } from '@/features/standings/driver-standings'
import { stubFetch, stubFetchError, stubFetchJson, stubFetchPending } from '@/test/fetch'
import { renderWithProviders } from '@/test/render'

const LIVE_RESPONSE = {
  data: [
    {
      position: 1,
      driverId: 'antonelli',
      code: 'ANT',
      permanentNumber: 12,
      givenName: 'Andrea Kimi',
      familyName: 'Antonelli',
      nationality: 'Italian',
      countryCode: 'IT',
      constructorId: 'mercedes',
      constructorName: 'Mercedes',
      points: 242,
      wins: 6,
    },
  ],
  meta: {
    season: 2026,
    round: 12,
    fetchedAt: '2026-09-01T16:35:10Z',
    source: 'LIVE',
    stale: false,
  },
}

const OFF_SEASON_RESPONSE = {
  data: [],
  meta: {
    season: 2027,
    round: null,
    fetchedAt: '2027-01-04T10:00:00Z',
    source: 'LIVE',
    stale: false,
  },
}

describe('DriverStandings', () => {
  beforeEach(() => {
    stubFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows skeletons while the request is in flight', () => {
    stubFetchPending()
    renderWithProviders(<DriverStandings />)

    expect(screen.getByText('Loading driver standings')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
  })

  it('renders both presentations once loaded', async () => {
    stubFetchJson(LIVE_RESPONSE)
    renderWithProviders(<DriverStandings />)

    const table = await screen.findByRole('table')
    expect(within(table).getByText('242')).toBeInTheDocument()

    const list = screen.getByRole('list', { name: 'Driver championship standings' })
    expect(within(list).getByText('Andrea Kimi Antonelli')).toBeInTheDocument()
  })

  it('names the season from meta, not the calendar', async () => {
    stubFetchJson(LIVE_RESPONSE)
    renderWithProviders(<DriverStandings />)

    expect(await screen.findByText('Formula 1 2026 championship')).toBeInTheDocument()
  })

  it('shows an explicit off-season empty state rather than a blank table', async () => {
    stubFetchJson(OFF_SEASON_RESPONSE)
    renderWithProviders(<DriverStandings />)

    expect(await screen.findByText("The season hasn't started yet")).toBeInTheDocument()
    expect(screen.getByText(/No results have been recorded for the 2027 season/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows an error state when the API cannot be reached', async () => {
    stubFetchError()
    renderWithProviders(<DriverStandings />)

    await waitFor(() => {
      expect(screen.getByText('Standings unavailable')).toBeInTheDocument()
    })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 3: Wire the route**

Rewrite `frontend/src/App.tsx`:

```tsx
import { Navigate, Route, Routes } from 'react-router'

import { AppLayout } from '@/components/app-layout'
import { ApiStatus } from '@/features/health/api-status'
import { DriverStandings } from '@/features/standings/driver-standings'

/**
 * Routing shell. Feature routes are added as they land:
 *   /standings/constructors, /schedule, /results, /races/:season/:round
 *
 * /health keeps ApiStatus reachable for diagnosing the CORS path between the
 * two hosts, which is the thing most likely to be quietly broken in production.
 */
export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/standings/drivers" replace />} />
        <Route path="/standings/drivers" element={<DriverStandings />} />
        <Route path="/health" element={<ApiStatus />} />
      </Routes>
    </AppLayout>
  )
}
```

- [ ] **Step 4: Verify**

Run: `cd frontend && npm run lint && npm run typecheck && npm test && npm run build`
Expected: all pass, 26 tests.

- [ ] **Step 5: Verify in a browser against the real backend**

Terminal 1: `cd backend && ./mvnw spring-boot:run`
Terminal 2: `cd frontend && npm run dev`

Open `http://localhost:5173`. Expected: redirect to `/standings/drivers`, 23 real drivers, flags rendering. Then check by hand:
- Narrow the window below 768px — the table is replaced by cards, with no horizontal scrollbar on the page.
- Tab through the page — the skip link works and focus is visible on every stop.
- Toggle dark and light mode — both readable.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/standings/driver-standings.tsx \
        frontend/src/features/standings/driver-standings.test.tsx \
        frontend/src/App.tsx
git commit -m "feat(web): driver standings page at /standings/drivers

Owns the three states PRD 9 requires so the presentation components stay pure
functions of their rows. Off-season renders an explicit 'season hasn't started'
message rather than an empty table, per PRD 4.1.

ApiStatus moves to /health rather than being deleted: it is the cheapest way
to diagnose the cross-host CORS path in production."
```

---

## Task 11: Definition of done

**Files:**
- Modify: `docs/decisions.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Measure contrast in both themes**

The new muted text in this slice is the constructor name, nationality subtitle, position column and header labels — all `text-muted-foreground`. `docs/decisions.md` already records light-theme muted-on-card at **4.74:1** against a 4.5 threshold, so this needs re-measuring, not assuming.

Resolve the OKLCH token values from `frontend/src/index.css` to sRGB and compute WCAG ratios for each new pairing, in **both** themes:

- `--muted-foreground` on `--card` (table cells, card subtitles)
- `--foreground` on `--card` (driver names, points)
- `--destructive` / `--destructive-foreground` in the error Alert

Record the actual numbers. If any pair is below 4.5:1, darken the token and re-measure before proceeding — do not ship an assumption.

- [ ] **Step 2: Append the decision-log entry**

Add to the end of `docs/decisions.md`:

```markdown
---

## 2026-09-01 — `feature/driver-standings`

### PRD 6.3 names a Resilience4j artifact that does not exist

PRD 6.3 specifies Resilience4j `2.4.0` via `resilience4j-spring-boot4`. Neither
exists on Maven Central: the newest published starter is
`resilience4j-spring-boot3`, newest version `2.3.0`.

- **Chosen:** `resilience4j-spring-boot3:2.3.0` on Boot 4, plus
  `spring-boot-starter-aspectj` (`spring-boot-starter-aop` has no Boot 4 release).
- **Verified by spike, not assumed:** a throwaway test confirmed `@Retry`
  intercepts on Boot 4.1.0 / Java 25 and records 3 attempts. The starter's
  autoconfiguration registration file and the `@ConditionalOnClass` /
  `@EnableConfigurationProperties` annotations it depends on are all still
  present in Boot 4.
- **Rejected:** Spring Framework 7's native `@Retryable`
  (`org.springframework.resilience.annotation`), which is already on the
  classpath, needs zero dependencies, and was *also* verified working in the same
  spike. It covers maxRetries, delay, multiplier, jitter and maxDelay — genuinely
  sufficient for this slice. Resilience4j was kept only because F2 wants its
  circuit breaker and F3 wants its Micrometer metrics; if those plans change,
  Spring's native retry is the smaller answer.
- **Risk accepted:** this is a Boot 3 starter on Boot 4 — an unsupported
  combination that happens to work. Re-run the retry tests on every Boot upgrade,
  and treat a failure there as a signal to switch to Spring's native retry rather
  than to debug Resilience4j.
- **PRD 6.3 should be amended.**

### Jackson 3's package split is not uniform

Boot 4 moved databind to `tools.jackson.databind`, but annotations stayed at
`com.fasterxml.jackson.annotation` (artifact `jackson-annotations:2.21`). So a
single file imports `@JsonProperty` from the old coordinates and `ObjectMapper`
from the new ones. Also: `tools.jackson.core.JacksonException` extends
`RuntimeException`, so there is no checked exception to declare and an unusable
response body will escape as a 500 unless caught explicitly — which is why
`DriverStandingsService` catches `JacksonException` alongside
`RestClientException`.

### Error translation lives in the service, not the client

`@Retry` works through a Spring proxy, so a `try/catch` inside
`JolpicaClient.fetchCurrentDriverStandings()` would swallow failures before the
retry ever saw them. `JolpicaClient` therefore lets `RestClientException`
propagate and `DriverStandingsService` — a different bean, so no self-invocation
— translates it to `UpstreamUnavailableException`.

- **Rejected:** a Resilience4j `fallbackMethod`, which would work but puts the
  translation in the class whose job is transport, and is harder to test in
  isolation.

### Upstream outages are 503, not 500

The scaffold's `GlobalExceptionHandler` mapped every unrecognised exception to
500 `INTERNAL_ERROR`. A Jolpica outage is a dependency being down, not a defect
here, and the frontend needs to tell those apart. Added
`UpstreamUnavailableException` → 503 `UPSTREAM_UNAVAILABLE`, logged at warn
rather than error so it does not read like our bug in the logs.

### Mobile: card list, as two sibling DOM trees

Below `md` the table is replaced by a card list. Everything stays visible, full
driver names fit, and there is no horizontal scroll gesture to miss.

- **Rejected:** horizontal scroll (cheapest, but Wins is invisible until a
  gesture users most often miss, and the container needs `tabindex="0"` plus an
  accessible name to be keyboard-reachable); column priority, dropping Wins on
  mobile (loses data outright, and the hiding rule would need revisiting when F8
  adds podiums and DNFs).
- **Implementation constraint:** two sibling trees switched by CSS, *not* one
  `<table>` restyled with `display` swaps — that strips row and column semantics
  from assistive tech. Screen readers honour `display: none`, so only one tree is
  ever announced, and 23 duplicated rows cost nothing.
- **Consequence for tests:** both trees exist in jsdom regardless of viewport, so
  every query must be scoped to the tree under test. An unscoped
  `getByRole('table')` passes while asserting against the wrong element.

### The driver's current team is the LAST constructor

Jolpica's `Constructors` is an array, ordered with the current team last, and it
is already non-singular in live data — `lawson` switched teams during 2026.

- **Rejected:** the first element, which on today's live data shows the team the
  driver *left*; returning the whole list, which turns a single table column into
  a collection and adds UI and a11y surface for a rare case.
- **Guarded:** `lawson`'s real two-constructor payload is a fixture, so an
  upstream reordering fails a test rather than mislabelling a driver.

### Points are BigDecimal

Half points are real: 2021 returns `"395.5"`, and any race abandoned early awards
them. `int` would throw. `BigDecimal` serialises as a bare JSON number, so `242`
stays `242` on the wire.

### Flags served from `public/`, not imported from `src/assets/`

The filename is computed from the backend's ISO code at runtime, and Vite's asset
imports need static specifiers. `public/flags/<iso>.svg` avoids an import map for
16 tiny files, and CSP `img-src 'self'` already permits it.

- **Rejected:** an npm flag package (hundreds of unused assets, and a new
  dependency in a repo whose audit surface we are shrinking); emoji flags (two
  boxed letters on Windows, and PRD 5 commits to Edge).
- **Source and licence:** _record the set and licence used here._

### No MSW on the frontend

`api-status.test.tsx` predicted that F1 would justify a mock-service layer. It
does not: there are two endpoints. The fetch stubs were extracted to
`src/test/fetch.ts` instead. Revisit when the endpoint count or response-shape
variety actually grows.

### Jolpica caches its own responses for 10 minutes

Upstream sends `cache-control: max-age=600`. PRD 10 specifies a 1–5 minute Redis
TTL, which therefore buys no extra freshness — a shorter TTL just re-fetches a
response Jolpica is still serving from its own cache. **F2 should revisit the TTL
figure.** Also noted: Jolpica exposes no rate-limit headers, so retry backoff is
necessarily blind rather than adaptive.

### Contrast measured, not assumed

_Record the measured ratios from Step 1 here, for both themes._
```

Replace both italicised placeholders with real values before committing.

- [ ] **Step 3: Update the README roadmap**

In `README.md`, change the Roadmap line from:

```
Scaffold → driver standings → resilience chain → observability → rate limiting →
```

to:

```
Scaffold ✓ → driver standings ✓ → resilience chain → observability → rate limiting →
```

- [ ] **Step 4: Full verification**

```bash
cd backend  && ./mvnw -B verify
cd ../frontend && npm run lint && npm run typecheck && npm test && npm run build && npm audit --audit-level=high
```

Expected: backend BUILD SUCCESS 57 tests; frontend all green, 26 tests.

`npm audit` may report the pre-existing `shadcn`-chain advisories — those are not
from this slice and are tracked separately against the open Dependabot PRs.

- [ ] **Step 5: Commit and open the PR**

```bash
git add docs/decisions.md README.md
git commit -m "docs: record driver standings decisions and measured contrast"
git push -u origin feature/driver-standings
gh pr create --fill
```

Merge only when CI is green: `gh pr merge --squash --delete-branch`

---

## Self-Review

**Spec coverage.** Walked every section of the spec against the tasks:

| Spec requirement | Task |
|---|---|
| 7 `jolpica/dto` records, consumed fields only | 1 |
| Fixtures captured from live API | 1 |
| Off-season shape (`total: 0`, `round: null`) | 1, 6, 10 |
| 16-demonym ISO map, nullable, warning | 2 |
| `RestClient` + timeouts | 3 |
| Resilience4j retry, 503 → 503 → 200 | 4 |
| `UpstreamUnavailableException` → 503 | 4, 6 |
| Last-constructor rule, pinned by `lawson` fixture | 1, 5, 6 |
| `BigDecimal` points / `395.5` | 5, 6 |
| `data` = rows only, season/round in `meta` | 6 |
| `ApiResponse.live()`, no new factory | 6 |
| RTK Query endpoint + `DriverStandings` tag | 7 |
| Flag renders nothing on null, decorative | 8 |
| Two sibling trees, real table semantics | 9 |
| Loading / empty / error states | 10 |
| Route + `/` redirect | 10 |
| AA contrast measured in both themes | 11 |
| `docs/decisions.md` entry | 11 |
| README roadmap line | 11 |

Two spec items are intentionally **not** tasks, and both are recorded as
non-goals in the spec itself: the PRD §7 dual-mapping contract test (needs F2's
snapshot path) and the "Last updated" banner (nothing can set `stale` without a
cache tier).

**Deviations from the spec, both deliberate and both documented in Task 11:**
flags live in `public/flags/` rather than `src/assets/flags/`; and `ApiStatus`
moves to `/health` rather than being retired outright, so the cross-host CORS
path stays diagnosable in production.

**Placeholder scan.** Two intentional fill-ins remain in Task 11's decision-log
text — the flag set's licence and the measured contrast ratios — because both are
values the implementer produces. Each is marked italic with an explicit
instruction to replace before committing. No other placeholders.

**Type consistency.** Checked names and signatures across tasks:
`fetchCurrentDriverStandings()` (Tasks 3, 4, 6), `toIsoCode(String)` (Tasks 2, 5),
`toRows(List<JolpicaDriverStanding>)` (Tasks 5, 6), `currentDriverStandings()`
(Task 6), `DriverStandingRow` components identical between the Java record
(Task 5) and the TS type (Task 7), `useDriverStandingsQuery` (Tasks 7, 10),
`<Flag countryCode>` (Tasks 8, 9), `rows` prop (Tasks 9, 10), and the
`stubFetch*` helpers (Tasks 7, 8, 10). One mismatch found and fixed while
writing: `permanentNumber` is `Integer` (nullable) in Java, so the TS type is
`number | null`, not `number`.

**Test-count arithmetic.** 14 existing → +5 (T1) → +21 (T2) → +3 (T3) → +2 (T4)
→ +7 (T5) → +5 (T6, four controller plus enabling the disabled 503 test) = 57
backend. Frontend 8 existing → +4 (T8) → +9 (T9, four table plus five cards) → +5 (T10) = 26.
