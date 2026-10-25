# Correctness audit — 2026-10-25

This round follows `aacea48` and checks application behavior against source, existing tests, new failing regressions, PostgreSQL, and browser flows. Existing uncommitted fund-provider/configuration edits in the primary workspace were excluded using an independent worktree.

## Confirmed repairs

| Area | Trigger and resulting behavior |
| --- | --- |
| XIRR | Same-day cancelling flows previously produced an arbitrary near -100% annualized rate. Net flows per date and return null when no dated investment interval remains. Long holding periods continue searching usable probe rates after numerical overflow. |
| Zero balances | Fully withdrawn accounts previously lost their latest performance endpoint. Retain zero valuations, distinguish withdrawals from actual full losses, and handle funding after an empty account without dividing by zero. |
| Performance chart | A valid server level of zero now plots a -100% return after a positive baseline. Invalid benchmark prices still remain gaps. |
| CSV structure | Reject duplicate/aliased headers, mismatched column counts, and malformed quoting. Parse quoted commas, escaped quotes, multiline notes, CRLF, and UTF-8 BOM correctly. |
| Transaction precision | Reject quantities beyond 12 integer/8 fractional digits and prices/amounts/fees beyond 14 integer/6 fractional digits before database writes. Trailing zeros remain valid. Huge scientific exponents never expand into huge fingerprint strings. Preview and direct commit use consistent trimmed dates. DIVIDEND/FEE reject ignored trade fields. |
| API request errors | Malformed JSON, UUIDs, dates, and framework-level client errors retain 4xx status instead of becoming generic HTTP 500. Public error bodies do not echo raw invalid payloads. |
| Fund confirmation | A known future confirmation date remains pending until the Shanghai business date reaches it. Future NAV does not create an execution. |
| Manual/reporting NAV | Reject future manual NAV and exclude future/invalid stored NAV from current combined reporting. |
| Pending purchase settlement | Generating a report could compute newly available shares without persisting them because the outer transaction was read-only. Use a writable report transaction and verify the saved shares directly in PostgreSQL before any subsequent purchase-list request. |
| Fund editors | Ignore late metadata responses after code changes or editor closure; reset purchase editors when selecting another fund. Purchase/NAV defaults use Shanghai dates rather than UTC dates. |
| Settings | Preserve unsaved selections during background refresh, show failed saves, and prevent editing controls during an active save. |

## Validation

Regression tests were added before the corresponding repairs and their failures were observed. The database rollback test now uses a temporary constraint in its disposable PostgreSQL container: invalid precision is deliberately rejected before a database flush, so it is no longer a valid fixture for testing a flush-time failure.

- Java 21 `./gradlew test postgresTest --no-daemon`: 264 regular tests and 11 real PostgreSQL tests; zero failures, errors, or skips. This includes all Flyway migrations, schema validation, transaction rollback, and persisted NAV settlement.
- `NODE_ENV=test npm --prefix apps/web test`: 144 tests across 24 files passed.
- `npm --prefix apps/web run lint`: passed with zero warnings.
- `npm --prefix apps/web run build`: TypeScript compilation and production bundling passed.
- Shell syntax validation for deployment/E2E scripts and production Compose configuration validation: passed.
- Full Playwright suite on the final source: 12/12 passed in 24.1 seconds, including login/session, core ledger journey, CSRF, provider outages, CSV rollback, backdated history, contribution classification, and cross-market chart behavior.
- 24 new backend test cases and 8 new frontend test cases were added. Existing regression assertions were also strengthened.

Full-suite evidence is in `/tmp/dca-audit-backend-final.log`, `/tmp/dca-audit-web-final.log`, `/tmp/dca-audit-lint-final.log`, `/tmp/dca-audit-build-final.log`, and `/tmp/dca-audit-e2e-final.log` on the verification host. Docker builds route required downloads through the host proxy at `localhost:7890`; Chromium uses the already installed executable. E2E uses its own Compose project on ports 38080/38081 and disposable volumes. A host Docker network-endpoint omission was diagnosed from actual container attachments and repaired only for the test API.

## Scope and limits

Source review and the existing suites cover FIFO/splits, cash replay, portfolio valuation, plans/contributions, market-data provider failure states, benchmarks, FX/reporting, authentication/session behavior, migrations, and UI/API adaptation. Browser tests use a local mock market-data provider and a separate temporary database. They do not certify live third-party service availability, every exchange closure, or every possible input/concurrency schedule. A passing audit does not prove the absence of all future bugs.

The production schema remains V001–V025; this round requires no migration. The primary workspace's pre-existing changes, persistent credentials, local Compose override, and production volumes are preserved. The existing large frontend chunk warning and Spring test annotation deprecations are not test failures.
