# Equivalent Nasdaq ETF executions

Plan targets QQQ and QQQM accept QQQ/QQQM purchases at 1×, QLD at 2× and TQQQ at 3×. These are explicit positive Nasdaq-100 exposure relationships. Any instrument explicitly listed in the cycle remains accepted as itself. Unknown symbols and inverse funds are not silently mapped to long Nasdaq exposure. If a cycle has both QQQ and QQQM as separate targets, an alternate execution is rejected as ambiguous rather than allocated arbitrarily or counted twice.

The same resolver is used for manual transaction create/update, CSV preview/commit cycle validation, per-asset cycle attribution, and plan allocation recommendations. Existing transactions can be edited to select DCA and the matching cycle. BUY-only, month, execution-window and initial-capital restrictions still apply.

Cash progress uses quantity × trade price + fee once. Target equivalent exposure uses quantity × trade price × multiplier and excludes fees. For 60 TQQQ shares bought at USD 52.08 with no fees, cash progress is USD 3,124.80 and QQQ/QQQM-equivalent target daily exposure is USD 9,374.40. Cycle completion and contribution totals remain based on USD 3,124.80. The per-asset response adds `equivalentExposureAmount` and `executions` details (actual symbol, cash paid, exposure multiplier, equivalent exposure). The Plan page displays leveraged execution details below the cycle progress.

Recommendations include the current market value of mapped holdings multiplied by the target daily exposure factor, alongside direct target holdings and the existing CNY fund overlay. Real portfolio holdings, cash, FIFO, P/L and return calculations continue to use actual trades and prices. The plan target itself is not rewritten, and no synthetic QQQM shares or funding events are inserted.

TQQQ targets 3× the **daily** performance of the Nasdaq-100; QLD targets 2×. These multipliers describe target daily exposure, not a guaranteed multi-day return relationship. Sources: [ProShares TQQQ](https://www.proshares.com/our-etfs/leveraged-and-inverse/tqqq), [ProShares QLD](https://www.proshares.com/our-etfs/leveraged-and-inverse/QLD).

Regression coverage includes linking an existing TQQQ BUY, matching and unrelated/inverse instruments, preserving period/type validation, exact cash and separate exposure totals, direct leveraged plan targets, ambiguous aliases, recommendation exposure, and UI/normalizer preservation of decimal strings. This change does not implement options, futures, or arbitrary contract multipliers.

Validation: 270 backend tests, 13 real PostgreSQL tests and 148 frontend tests passed; frontend lint, TypeScript/production build and backend bootJar passed.
