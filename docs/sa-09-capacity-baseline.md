# SA-09 capacity baseline

> Historical H2 capacity evidence for the SA-09 commit only. It is not a
> current PostgreSQL production benchmark; see
> [`next-development-plan.md`](./next-development-plan.md) for follow-up work.

Generated data, not a real account. No speculative index or cache was added.

Measured in `CapacityBaselineTest` on H2 (`application-test`), Java 21, after 9A shared current ledger projection.

- Instruments: 20
- Transactions: 10000
- Daily prices: 36540 rows (5 years from 2021-08-27 to 2026-08-27)
- Clock date used for generation: 2026-08-27

| Workload | Elapsed ms | Prepared statements | Query executions | Entity loads |
| --- | ---: | ---: | ---: | ---: |
| currentViews (shared ledger) | 1931 | 26 | 6 | 46560 |
| independent summary+holdings+allocation | 1686 | 56 | 16 | 139660 |
| dashboard currentViews + history(1Y) | 2138 | 31 | 11 | 93100 |
| history(1Y) | 811 | 5 | 5 | 46540 |
| transaction list | 104 | 1 | 1 | 10020 |

Observation: currentViews used 26 prepared statements and 46,560 entity loads versus 56 / 139,660 for three independent current-ledger rebuilds. Wall-clock of the first currentViews call can be similar to later independent calls because of JVM/Hibernate warmup; statement and entity-load counts are the stable comparison. history stayed on its own snapshot/replay path. Existing trade-date and instrument-date indexes were sufficient for this generated volume; no schema change.

The remaining cost is loading five years of daily bars for current valuation (about 36,540 price entities). That is a query-shape issue, not a missing index. No cross-request projection cache was added.
