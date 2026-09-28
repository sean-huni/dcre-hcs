# dcre-hcs

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

Holiday Calendar Sync (R-38): the DCRE clock job that keeps the `public_holiday` reference calendar
fresh so the process-date roll never misdates.

## What it does

HCS is the single writer of `public_holiday` (R-04). Each run fetches public holidays for the base
year and the next year per country (default `ZA`) from Nager.Date, with an optional key-gated
Calendarific-compatible fallback, and upserts them keyed `(country, holiday_date)`, so every window
is idempotent and self-correcting. It is a one-shot Spring Boot 4.1 / Spring Batch 6 job: it runs,
exits with the Batch outcome as its exit code, and holds no state between runs.

Trigger: AGT launches it. `HCS` is a constant of AGT's `Stage` enum (the one cross-family stage), and
AGT's `HcsScheduler` mints one Kubernetes Job per window of `AGT_HCS_INTERVAL_HOURS` (default 6) with
job parameters `sync.date=<today>`, `window=w<epochSeconds/(hours*3600)>` and `countries=ZA`. The
image comes from `AGT_HCS_IMAGE`; unset, AGT does not launch HCS. (AGT `origin/dev`, checked
2026-09-28.)

Downstream, `dcre-cde` reads the calendar over its own holidays datasource
(`DCRE_CDE_HOLIDAYS_DB_URL`, which AGT sets to the same HCS database URL) and fails closed when a
collection year has no rows; the next HCS window self-heals it.

## Architecture and principles

### Databases today

- **Primary datasource** `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. The committed default is
  `dcre_col` on `localhost:26257`. Under AGT the pod receives `DCRE_DB_URL` from AGT's
  `StageDatabases`, which for HCS resolves `AGT_HCS_SERVICE_DB_URL`, default
  `jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_hcs?sslmode=disable` (AGT `origin/dev`,
  checked 2026-09-28). The service itself writes to whichever database that URL names; its code does
  not check the database name.
- **Heartbeat datasource** `DCRE_AGTOPS_DB_URL` / `DCRE_AGTOPS_DB_USER` / `DCRE_AGTOPS_DB_PASSWORD`,
  default `agt_ops` on `localhost:26257`, used only by the platform `HeartbeatWriter`.

### What it writes and reads

| Object | Where | Access |
|---|---|---|
| `public_holiday` (`id UUID PK`, `country`, `holiday_date`, `local_name`, `name`, `is_global`, `version`, `created_at`, `updated_at`, `UNIQUE (country, holiday_date)`) | primary | written only by HCS |
| `HCS_BATCH_*` Spring Batch metadata | primary | written by the persistent `JobRepository` from platform-batch `BatchJdbcConfig` (`dcre.batch.table-prefix: HCS_BATCH_`) |
| `hcs_databasechangelog` / `hcs_databasechangeloglock` | primary | Liquibase history for this service only |
| `launch_intent.heartbeat_at`, `launch_intent.owner_pod` | `agt_ops` | UPDATE for the row keyed by `JOB_NAME`, only while the job runs and only when `JOB_NAME` is set |
| `<exchange-root>/outcomes/<JOB_NAME>` | filesystem | `BUSINESS_ACCEPTED` on `COMPLETED` |

It reads nothing from any other service's tables. Liquibase changelogs (`db/changelog/db.changelog-master.xml`):
`2026/07/001-hcs.xml` creates `public_holiday` with an `<sql>` `CREATE TABLE IF NOT EXISTS` block;
`2026/07/002-batch-metadata.xml` applies `batch-metadata-hcs.sql` via `<sqlFile>`.

### Invariants and principles

- SOLID, 3-tier: `SyncTasklet` is a thin Spring Batch entry adapter (single-tasklet job `hcsJob`,
  step `syncStep`); business logic lives in `HolidaySyncService`; persistence happens only via
  `data/repo/PublicHolidayRepo`. The HTTP sources sit behind the `HolidayProvider` interface:
  `NagerClient` (primary), `FallbackHolidayClient` (optional), composed by
  `ResilientHolidayProvider`. Layer-first packages: `config/`, `service/`, `data/model/` (entities
  extend the platform `BaseEntity`), `data/repo/`.
- Idempotent writes on the full business identity: `PublicHolidayRepo.upsert` is
  `INSERT ... ON CONFLICT (country, holiday_date) DO UPDATE`, never CRDB `UPSERT INTO` (which
  arbitrates on the PK only).
- Restart semantics: identifying job parameters `sync.date` + `window` make each window a distinct
  job instance; a startup `ApplicationRunner` (`@Order(-10)`) runs `StaleExecutionSweeper.abandonStale`
  over `HCS_BATCH_` executions older than 60 minutes, so a killed pod's same-identity relaunch never
  hits `JobExecutionAlreadyRunningException`. A stale Liquibase lock is released by platform-batch's
  `LiquibaseLockAutoConfiguration`.
- Resilience: a manual resilience4j core circuit breaker (`ResilienceConfig`: slidingWindowSize 4,
  minimumNumberOfCalls 2, waitDurationInOpenState 30s; only the core library, because the
  resilience4j Spring Boot modules are unverified on Boot 4). Breaker state is per run: its value is
  skipping the dead primary for the remaining country/year fetches. With the fallback disabled (the
  default), a non-2xx from Nager fails the job: level-triggered, the next window retries (R-38).
- Lifecycle seam: the platform `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` on `COMPLETED`
  (R-35); `ExitCodeMain` maps the Batch outcome to the JVM exit code (R-34); `HeartbeatWriter` lets
  AGT see a wedged-but-alive pod.
- 12FactorApp Alignment - https://12factor.net/: committed working dev defaults in
  `application.yml` with env overrides (a clean clone runs with no `.env`), stateless one-shot
  process, CockroachDB and the holiday APIs as attached resources.

### Public holiday API

Primary: Nager.Date (https://date.nager.at), `GET {base-url}/api/v3/PublicHolidays/{year}/{country}`,
keyless. Per holiday HCS takes `date`, `localName`, `name`, `global`.

Fallback: any Calendarific-compatible API (https://calendarific.com),
`GET {base-url}/api/v2/holidays?api_key=...&country=...&year=...`. Key-gated via
`DCRE_HCS_FALLBACK_API_KEY` and disabled by default, so a clean clone runs keyless with the
fail-and-retry semantics. Field mapping: the Calendarific `name` fills both `name` and `local_name`;
`is_global` is true when the holiday's `type` array contains `National holiday`.

## Prerequisites

- Java 25 (`.sdkmanrc`: `java=25-tem`; `build.gradle` sets `sourceCompatibility` /
  `targetCompatibility` 25); Gradle wrapper 9.5.1 (committed); Spring Boot 4.1.0
- Docker (Testcontainers CockroachDB in tests, image builds)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and
  `za.co.fnb.dcre:platform-batch:0.1.0` (resolved via `mavenLocal()`)
- For cluster runs: the `dcre-infra` kind cluster `dcre-dev`

## Quickstart

```bash
# 1. Publish the platform libs (paths assume the fleet checkout be/java/spring/dcre/{shared,platform}/)
(cd ../../platform/platform-persistence && ./gradlew publishToMavenLocal)
(cd ../../platform/platform-batch && ./gradlew publishToMavenLocal)

# 2. Build + test: clean clone, no .env needed (working defaults are committed)
./gradlew build

# 3. One-shot local run against CockroachDB on localhost:26257
#    (dcre-infra compose.yml, or a port-forward to the kind cluster)
./gradlew bootRun --args='sync.date=2026-07-15 window=w1'
```

`platform-batch` needs `platform-model` and `platform-files` published first; its README has the
chain. Job parameters: `sync.date` (identifying, base-year source, `YYYY-MM-DD`), `window`
(identifying), `countries` (optional, comma-separated, default `ZA`).

## Configuration

Precedence: `application.yml` default < environment variable. Spring relaxed binding lets any
property be overridden by its environment-variable form, so this table lists the variables the
committed `application.yml` and the platform libraries name, not a closed set.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | Primary datasource (AGT sets it to the HCS database URL, see above) |
| `DCRE_DB_USER` | `root` | DB username |
| `DCRE_DB_PASSWORD` | empty | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat target |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat user |
| `DCRE_AGTOPS_DB_PASSWORD` | empty | Heartbeat password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam (`outcomes/<JOB_NAME>`); AGT sets `/exchange`. Relative to the working directory: from the fleet checkout `be/java/spring/dcre/shared/hcs` five `../` resolve to `be/infra/dcre-infra/exchange`, which does not exist (the stage services use six), so set it explicitly for a local run |
| `DCRE_HCS_BASE_URL` | `https://date.nager.at` | Nager.Date base URL (primary holiday API) |
| `DCRE_HCS_FALLBACK_BASE_URL` | `https://calendarific.com` | Calendarific-compatible fallback base URL |
| `DCRE_HCS_FALLBACK_API_KEY` | empty (fallback disabled) | Fallback API key; empty keeps the keyless fail-and-retry behaviour |
| `JOB_NAME` | unset: outcome file `local-hcs-<executionId>`, heartbeat off | K8s Job name, set by AGT |

Fixed in `application.yml` rather than env-driven: `dcre.batch.table-prefix: HCS_BATCH_` and the
Liquibase history tables `hcs_databasechangelog` / `hcs_databasechangeloglock`. Platform-batch
properties such as `dcre.batch.heartbeat-seconds` (default 10) and `dcre.liquibase.lock-ttl`
(default 60s) are documented in that library's README.

## Testing

```bash
./gradlew test   # Docker required (Testcontainers cockroachdb/cockroach:v26.2.3)
```

HTTP sources are stubbed with the JDK's `com.sun.net.httpserver` (no extra dependency):

- `HcsJobTest`: sync writes current + next year rows and a second window is an upsert no-op; the seam
  name falls back to a self-describing local name without `JOB_NAME`.
- `HcsJobFailureTest` (same file): a Nager 500 fails the job (level-triggered, R-38).
- `HcsFallbackTest`: primary always-500 plus a Calendarific-shaped stub and an API key syncs
  successfully, and the open breaker stops calls to the dead primary (exactly 2 primary calls for 4
  fetches).
- `HcsFallbackDisabledTest` (same file): primary down with no API key still fails the job.
- `CucumberSuiteTest` (`features/holiday-sync.feature`): three BDD scenarios covering sync,
  idempotent resync with field refresh, and an upstream 500 leaving existing rows untouched.

## Local cluster deployment

```bash
./gradlew bootJar                                  # build/libs/hcs-2.0.jar (version from build.gradle)
docker build -t dcre-hcs:<tag> .                   # Dockerfile: eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-hcs:<tag>
```

AGT then launches the image as an ephemeral Kubernetes Job per window: point `AGT_HCS_IMAGE` at
`dcre-hcs:<tag>`; the interval is `AGT_HCS_INTERVAL_HOURS` (default 6), the database
`AGT_HCS_SERVICE_DB_URL`. `dcre-infra`'s `scripts/switch-version.sh VERSION` repoints the fleet's
stage images, HCS included (dcre-infra `origin/dev`, checked 2026-09-28). Releases are digits-only three-component SemVer git tags (no `v`
prefix), uniform across the fleet.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
