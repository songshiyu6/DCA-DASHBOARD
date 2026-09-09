# China fund one-time purchases

## Scope

China mutual funds support two independent investment sources:

- persisted one-time purchases (`fund_purchase`), representing real CNY purchases entered by the user;
- automatic DCA rules (`auto_dca_rule`), whose daily executions remain rebuildable projections from the fund calendar and NAV history.

One-time purchases are intentionally not written to `investment_transaction`. The existing transaction and cash-ledger model is USD-only, so mixing CNY purchases into that table would corrupt USD cash accounting.

## Purchase facts

A one-time purchase stores the purchase date, gross cash paid, purchase fee rate, exact unit NAV, calculated fee, net subscribed amount and acquired shares. The exact NAV must already exist in `fund_nav_daily` for the purchase date. If it is missing, the caller must sync NAV or add that date manually first.

The NAV and acquired shares are captured when the purchase is recorded. Later NAV synchronization does not rewrite the purchase facts. Editing a purchase deliberately recalculates the fact set using the current exact NAV for the edited date.

## Reporting

Both one-time purchases and automatic DCA contribute to CNY fund holdings and multi-currency performance. Their gross CNY cash paid is treated as an external capital flow and converted at historical USD/CNY for the activity date. Current fund value uses cumulative acquired shares and the latest available fund NAV, with the same missing-NAV completeness rules used by automatic DCA.

The combined reporting flow model is:

`USD_CASH_LEDGER_PLUS_CNY_FUND_ACTIVITY_AT_HISTORICAL_USDCNY`

CNY fund activity remains separate from the USD cash ledger.

## Deletion

Deleting a one-time purchase removes its shares and external flow from fund holdings and performance. Deleting a fund also deletes its one-time purchases, automatic DCA rules, fund NAV and profile. The shared China trading calendar is preserved.
