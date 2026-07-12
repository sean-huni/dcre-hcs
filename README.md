# dcre-hcs

Holiday Calendar Sync (R-38). Single writer of public_holiday (R-04): syncs ZA public holidays from Nager.Date (current + next year per country) via idempotent INSERT ... ON CONFLICT upserts. Clock-launched every 6h by AGT (identifying params sync.date + window). 3-tier: SyncTasklet -> HolidaySyncService -> ResilientHolidayProvider (NagerClient + optional FallbackHolidayClient) + data/repo. With the fallback disabled (default), non-2xx from Nager fails the job (level-triggered, next window self-heals).

Spring Boot 4.1 / Spring Batch / Java 25, on the DCRE platform libs: `dcre-platform-persistence` (BaseEntity, JdbcConfig) and `dcre-platform-batch` (ExitCodeMain R-34, OutcomeFileWriter R-35, StaleExecutionSweeper).

## Consumers

The calendar feeds the R-38 process-date roll: `dcre-cde` reads `public_holiday` via a read-only view repo (never writes it, R-04/R-06) and rolls `Process_Date` forward one day at a time while the date is a Sunday or a ZA public holiday; `dcre-crw` then emits on `process_date = runDate`. CDE fails closed when the calendar has zero rows for a collection year, self-healing once HCS's next window lands. Design: `dcre/docs/specs/2026-07-12-process-date-holiday-calendar-design.md`.

## Public holiday API

Primary source is Nager.Date (https://date.nager.at): `GET {base-url}/api/v3/PublicHolidays/{year}/{country}`, keyless. Per holiday HCS takes `date`, `localName`, `name` and `global`, upserted into `public_holiday` keyed `(country, holiday_date)`.

Fallback is any Calendarific-compatible API (`GET {base-url}/api/v2/holidays?api_key=...&country=...&year=...`), sitting behind a circuit breaker in `ResilientHolidayProvider`. It is key-gated via `DCRE_HCS_FALLBACK_API_KEY` and disabled by default: a clean clone still runs keyless with the original fail-and-retry semantics. Field mapping: Calendarific `name` fills both `name` and `local_name`; `is_global` is true when the holiday's `type` array contains `National holiday`.

Circuit breaker (resilience4j core library, manual bean in `ResilienceConfig`): slidingWindowSize 4, minimumNumberOfCalls 2, waitDurationInOpenState 30s. The JVM is an ephemeral Kubernetes Job, so breaker state is per-run: its value is skipping the dead primary for the remaining country/year fetches within one run, routing them straight to the fallback. On primary failure (or an open breaker) the fallback is tried when enabled; when the fallback is disabled or also fails, the exception is rethrown, so failure semantics are unchanged from the keyless setup: the job fails and the next 6h window retries (R-38).

## Job structure

Single-tasklet job `hcsJob` (step `syncStep`):

- `SyncTasklet` (thin entry adapter): reads job params, delegates, records the upsert count in the execution context.
- `HolidaySyncService` (business tier): per country fetches base year + next year, so CDE's forward roll always has next-January cover; upserts keyed `(country, holiday_date)` make each 6h window idempotent and self-correcting.
- `ResilientHolidayProvider`: circuit breaker around `NagerClient` (Spring `RestClient` adapter, `GET {base-url}/api/v3/PublicHolidays/{year}/{country}`), optional `FallbackHolidayClient` (see "Public holiday API" above); with the fallback disabled, non-2xx throws, failing the job.
- `PublicHolidayRepo.upsert`: `INSERT ... ON CONFLICT (country, holiday_date) DO UPDATE` on the business identity (never CRDB `UPSERT INTO`).

Job parameters: `sync.date` (identifying, base year source) + `window` (identifying, `w<epochHours/6h>` from AGT's HcsScheduler) + `countries` (non-identifying, comma-separated, default `ZA`).

Lifecycle plumbing:

- On `COMPLETED` a `SeamListener` writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>` (SYNTHETIC-CONTRACT seam, R-35; AGT treats absence as never-success, R-33).
- `ExitCodeMain` maps the Batch outcome to the JVM exit code (R-34) so the Kubernetes Job pod reports it.
- A startup `ApplicationRunner` (`@Order(-10)`) runs `StaleExecutionSweeper.abandonStale` over `HCS_BATCH_` executions older than 60 minutes before the job launches.

## Data

Liquibase-owned (`db/changelog/db.changelog-master.xml`), applied to the shared `dcre_collections` DB with per-service history tables `hcs_databasechangelog(+lock)`, the same isolation idea as the `HCS_BATCH_` metadata prefix:

- `001-hcs.xml`: `public_holiday` (`id UUID PK`, `country VARCHAR(2)`, `holiday_date DATE`, `local_name`, `name`, `is_global`, BaseEntity audit columns, `UNIQUE (country, holiday_date)`).
- `002-batch-metadata.xml`: Spring Batch metadata tables under the `HCS_BATCH_` prefix (`spring.batch.jdbc.initialize-schema: never`; Liquibase mints them).

## Configuration

12FactorApp Alignment (https://12factor.net/): committed working dev defaults in `application.yml`, env overrides; a clean clone runs with no `.env`.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | CockroachDB datasource |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../infra/dcre-infra/exchange` | Outcome-seam root |
| `DCRE_HCS_BASE_URL` | `https://date.nager.at` | Nager.Date base URL (primary holiday API) |
| `DCRE_HCS_FALLBACK_BASE_URL` | `https://calendarific.com` | Calendarific-compatible fallback base URL |
| `DCRE_HCS_FALLBACK_API_KEY` | empty (fallback disabled) | Fallback API key; empty keeps the keyless fail-and-retry behavior |
| `JOB_NAME` | `local-<executionId>` | Outcome filename (set by AGT on cluster Jobs) |

Resilience: the primary sits behind a per-run circuit breaker; the fallback only engages when its API key is set (see "Public holiday API").

## Local module dependencies

| Module | Version | Scope | Used for |
|---|---|---|---|
| `za.co.fnb.dcre:dcre-platform-persistence` | 0.1.0 | `implementation` | `BaseEntity` (audit columns on `PublicHolidayEntity`), `JdbcConfig` (Spring Data JDBC base, imported by `HcsApplication`) |
| `za.co.fnb.dcre:dcre-platform-batch` | 0.1.0 | `implementation` | `ExitCodeMain` (R-34 exit-code mapping in `HcsApplication`), `OutcomeFileWriter` (R-35 seam in `HcsJobConfig`), `StaleExecutionSweeper` (startup sweep in `HcsJobConfig`) |

Both resolve from Maven Local only (`mavenLocal()` in `build.gradle`): run `./gradlew publishToMavenLocal` in each module repo before building HCS (see each module README's Publishing section).

## Build & test

```bash
./gradlew build   # or ./gradlew test
```

`HcsJobTest` (Testcontainers CockroachDB v26.2.3 + a JDK `com.sun.net.httpserver` Nager stub, no extra dependency): sync writes current + next year rows, a second window is an upsert no-op; `HcsJobFailureTest` proves a Nager 500 fails the job (level-triggered, R-38). `HcsFallbackTest` proves the fallback path: primary always-500 plus a Calendarific-shaped stub and an API key syncs successfully, and the open breaker stops calls to the dead primary; `HcsFallbackDisabledTest` proves primary-down with no API key still fails the job.

## Run

Local one-shot against the `dcre-infra` compose stack:

```bash
./gradlew bootRun --args='sync.date=2026-07-12 window=w1'
```

Cluster: `docker build -t dcre-hcs:0.1.0 .` (temurin-25-jre-alpine, expects `build/libs/dcre-hcs-0.1.0.jar`); AGT launches it as an ephemeral Kubernetes Job every 6 hours (`agt.hcs-image` / `AGT_HCS_IMAGE`, window arithmetic in `HcsScheduler`).
