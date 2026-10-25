# UI reliability fixes — 2026-10-25

This change addresses reproduced frontend failures. Existing local fund-provider edits are outside this change.

## Behavior

- CSV previews are tied to an input revision. Editing text or selecting a file invalidates the preview immediately; late preview/file-read responses cannot overwrite newer input or enable an obsolete import. Preview and commit cannot overlap, and file-read errors allow recovery by pasting content.
- New transaction tickers, plan name/budget/start date/asset, and CSV contents start empty. Plan asset labels follow the selected symbol. Fees reject a lone decimal point while retaining empty-as-zero and ordinary decimal inputs.
- Session lookup failures offer a retry instead of leaving the workspace on an indefinite loading screen.
- Preference storage denial or quota exhaustion does not prevent login, language switching, benchmark selection, or ETF ordering. Preferences remain usable for the current page session when persistence fails.
- Deferred dialog autofocus respects focus already inside the dialog, avoiding interrupted typing immediately after opening it.
- Performance charts preserve explicit missing observations, reject invalid rebasing values, use the last same-day observation, and retain unavailable server results. Benchmark names are escaped in HTML tooltips. Superseded search results are hidden while the new query debounces.
- Calendar-only dates do not move to another day in timezones such as Pacific/Auckland. Timestamp formatting retains timezone conversion.

## Verification

- Regression tests first reproduced the CSV preview race, storage exception, unsafe tooltip label, invalid benchmark baseline/gaps, duplicate same-day points, sample defaults, invalid fee, session retry, date shift, and dialog autofocus issue.
- `NODE_ENV=test npm --prefix apps/web test`: 136 tests passed. The host exports `NODE_ENV=production`, so test mode must be selected explicitly when running React component tests here.
- Zod was upgraded from 3.25.76 to 4.6.7 to address the production dependency audit finding. The plan resolver distinguishes schema input from coerced output; execution-day payloads remain numeric. Production dependency audit reports zero vulnerabilities.
- Web production build and ESLint passed.
- `e2e/tests/06-ui-resilience.spec.ts`: three Chromium tests passed against the production frontend build: session retry with blocked storage, stale CSV preview rejection, and cross-market chart rendering with safe tooltips and failed storage writes.
- These browser tests intercept API responses and do not modify a real account. They do not constitute full backend/provider acceptance.
