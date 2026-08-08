# dcre-hcs

Holiday Calendar Sync (R-38): the DCRE clock job that keeps the `public_holiday` reference calendar fresh so the process-date roll never misdates.

## What it does

HCS is the single writer of `public_holiday` (R-04). Each run fetches public holidays for the base year plus the next year per country (default `ZA`) from Nager.Date, with an optional key-gated Calendarific-compatible fallback, and upserts them keyed `(country, holiday_date)`, so every window is idempotent and self-correcting. AGT launches it as a short-lived Kubernetes Job per window (`AGT_HCS_INTERVAL_HOURS`, default 6; window arithmetic in AGT's `HcsScheduler`). Downstream, `dcre-cde` reads the calendar through a read-only view repo and fails closed when it has zero rows for a collection year; the next HCS window self-heals it.

## Architecture and principles

- SOLID, 3-tier: `SyncTasklet` is a thin Spring Batch entry adapter (single-tasklet job `hcsJob`, step `syncStep`); business logic lives in `HolidaySyncService`; persistence happens only via `data/repo/PublicHolidayRepo`. The HTTP sources sit behind the `HolidayProvider` interface: `NagerClient` (primary), `FallbackHolidayClient` (optional), composed by `ResilientHolidayProvider`. Layer-first packages: `config/`, `service/`, `data/model/` (entities extend the platform `BaseEntity`), `data/repo/`.
- 12FactorApp Alignment - https://12factor.net/: committed working dev defaults in `application.yml` with env overrides (a clean clone runs with no `.env`), stateless one-shot process, CockroachDB and the holiday APIs as attached resources.
- Idempotent restart semantics: `PublicHolidayRepo.upsert` is `INSERT ... ON CONFLICT (country, holiday_date) DO UPDATE` on the business identity, never CRDB `UPSERT INTO` (which arbitrates on the PK only). Identifying job parameters `sync.date` + `window` make each window a distinct job instance; a startup `ApplicationRunner` (`@Order(-10)`) runs `StaleExecutionSweeper.abandonStale` over `HCS_BATCH_` executions older than 60 minutes so a killed pod's same-identity relaunch never hits `JobExecutionAlreadyRunningException`.
- Resilience: a manual resilience4j core circuit breaker (`ResilienceConfig`: slidingWindowSize 4, minimumNumberOfCalls 2, waitDurationInOpenState 30s; the resilience4j Spring Boot modules are unverified on Boot 4). The JVM is ephemeral, so breaker state is per-run: its value is skipping the dead primary for the remaining country/year fetches. With the fallback disabled (the default), a non-2xx from Nager fails the job: level-triggered, the next window retries (R-38).
- Lifecycle seam: on `COMPLETED` a `SeamListener` writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>` via the platform `OutcomeFileWriter` (R-35); `ExitCodeMain` maps the Batch outcome to the JVM exit code (R-34) so the Kubernetes Job pod reports it.

### Data

Liquibase-owned XML changelogs (`db/changelog/db.changelog-master.xml`) on the shared `dcre_col` DB, with per-service history tables `hcs_databasechangelog(+lock)`:

- `2026/07/001-hcs.xml`: `public_holiday` (`id UUID PK`, `country VARCHAR(2)`, `holiday_date DATE`, `local_name`, `name`, `is_global`, version + audit columns, `UNIQUE (country, holiday_date)`).
- `2026/07/002-batch-metadata.xml`: Spring Batch metadata tables under the `HCS_BATCH_` prefix (`spring.batch.jdbc.initialize-schema: never`; Liquibase mints them).

### Public holiday API

Primary: Nager.Date (https://date.nager.at), `GET {base-url}/api/v3/PublicHolidays/{year}/{country}`, keyless. Per holiday HCS takes `date`, `localName`, `name`, `global`.

Fallback: any Calendarific-compatible API (https://calendarific.com), `GET {base-url}/api/v2/holidays?api_key=...&country=...&year=...`. Key-gated via `DCRE_HCS_FALLBACK_API_KEY` and disabled by default, so a clean clone runs keyless with the original fail-and-retry semantics. Field mapping: the Calendarific `name` fills both `name` and `local_name`; `is_global` is true when the holiday's `type` array contains `National holiday`.

## Prerequisites

- JDK 25 (Gradle toolchain; Spring Boot 4.1.0, Spring Batch)
- Docker (Testcontainers CockroachDB in tests, image builds)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `za.co.fnb.dcre:platform-batch:0.1.0` (resolved via `mavenLocal()`)
- For cluster runs: the `dcre-infra` kind cluster `dcre-dev`

## Quickstart

```bash
# 1. Publish the platform libs (sibling repos, once per checkout)
(cd ../platform-persistence && ./gradlew publishToMavenLocal)
(cd ../platform-batch && ./gradlew publishToMavenLocal)

# 2. Build + test: clean clone, no .env needed (working defaults are committed)
./gradlew build

# 3. One-shot local run against CockroachDB on localhost:26257
#    (dcre-infra scripts/crdb-forward.sh port-forward, or its compose.yml)
./gradlew bootRun --args='sync.date=2026-07-15 window=w1'
```

Job parameters: `sync.date` (identifying, base-year source, `YYYY-MM-DD`), `window` (identifying, `w<epochSeconds/(intervalHours*3600)>` from AGT's `HcsScheduler`), `countries` (non-identifying, comma-separated, default `ZA`).

## Configuration

Precedence: `application.yml` default < environment variable.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | CockroachDB datasource |
| `DCRE_DB_USER` | `root` | DB username |
| `DCRE_DB_PASSWORD` | empty | DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam (`outcomes/<JOB_NAME>`) |
| `DCRE_HCS_BASE_URL` | `https://date.nager.at` | Nager.Date base URL (primary holiday API) |
| `DCRE_HCS_FALLBACK_BASE_URL` | `https://calendarific.com` | Calendarific-compatible fallback base URL |
| `DCRE_HCS_FALLBACK_API_KEY` | empty (fallback disabled) | Fallback API key; empty keeps the keyless fail-and-retry behavior |
| `JOB_NAME` | `local-<executionId>` | Outcome filename (set by AGT on cluster Jobs) |

## Testing

```bash
./gradlew test   # Docker required (Testcontainers cockroachdb/cockroach:v26.2.3)
```

HTTP sources are stubbed with the JDK's `com.sun.net.httpserver` (no extra dependency):

- `HcsJobTest`: sync writes current + next year rows; a second window is an upsert no-op; the
  seam filename is self-describing without `JOB_NAME`; and a nested context asserts a Nager 500
  fails the job (level-triggered, R-38).
- `HcsFallbackTest`: primary always-500 plus a Calendarific-shaped stub and an API key syncs
  successfully, the open breaker stops calls to the dead primary (exactly 2 primary calls for 4
  fetches), and a nested context asserts a down primary with no API key still fails the job.
- `CucumberSuiteTest` (`features/holiday-sync.feature`): positive + negative BDD scenarios covering sync, idempotent resync with field refresh, and an upstream 500 leaving existing rows untouched.

## Local cluster deployment

```bash
./gradlew bootJar                                  # build/libs/hcs-2.0.jar (version from build.gradle)
docker build -t dcre-hcs:2.1.1 .                   # eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-hcs:2.1.1
```

AGT launches the image as an ephemeral Kubernetes Job per window: image from `agt.hcs-image` / `AGT_HCS_IMAGE`, interval from `AGT_HCS_INTERVAL_HOURS` (default 6). `dcre-infra` `scripts/switch-version.sh VERSION` repoints the whole fleet, including `AGT_HCS_IMAGE=dcre-hcs:VERSION`. Releases are digits-only 3-component SemVer git tags (no `v` prefix), uniform across the fleet; current release: 2.1.1.

## Related repositories

- Orchestrator: https://github.com/sean-huni/dcre-agt
- Collections stages: https://github.com/sean-huni/dcre-crr, https://github.com/sean-huni/dcre-ctv, https://github.com/sean-huni/dcre-cde, https://github.com/sean-huni/dcre-crw, https://github.com/sean-huni/dcre-cir, https://github.com/sean-huni/dcre-cix, https://github.com/sean-huni/dcre-csx, https://github.com/sean-huni/dcre-cpx, https://github.com/sean-huni/dcre-crg
- Payments stages: https://github.com/sean-huni/dcre-prr, https://github.com/sean-huni/dcre-ptv, https://github.com/sean-huni/dcre-pai, https://github.com/sean-huni/dcre-prw, https://github.com/sean-huni/dcre-pir
- Platform libs: https://github.com/sean-huni/dcre-platform-model, https://github.com/sean-huni/dcre-platform-files, https://github.com/sean-huni/dcre-platform-batch, https://github.com/sean-huni/dcre-platform-persistence, https://github.com/sean-huni/dcre-platform-copybook
- Infra + tooling: https://github.com/sean-huni/dcre-infra, https://github.com/sean-huni/dcre-fixture-toolkit, https://github.com/sean-huni/dcre-design-register, https://github.com/sean-huni/dcre-rpt
