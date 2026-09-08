import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { DataStateBanner, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import { api } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import type { PerformanceRange } from '../reportingTypes'

const ranges: PerformanceRange[] = ['1M', '3M', '1Y', 'YTD', 'ALL']

function copy(isZh: boolean) {
  return isZh ? {
    eyebrow: '多币种 · USD 报告口径', title: '统一资产报告', subtitle: '把实际 USD 账户与 CNY 国内基金自动定投派生资产按历史汇率统一折算为 USD。',
    boundary: '这是报告投影，不是多币种现金账本。CNY 基金仍由规则 + NAV 派生；真实交易流水目前仍为 USD。',
    combinedValue: '合并总价值', combinedPnl: '合并盈亏', combinedFlow: '累计外部投入', twr: '时间加权收益率', xirr: 'XIRR', cagr: '成立以来 CAGR', drawdown: '最大回撤',
    usdAccount: 'USD 实际账户', cnyFunds: 'CNY 基金市值', cnyFundsUsd: 'CNY 基金折合 USD', fundFlow: 'CNY 定投折合投入', fx: 'USD/CNY', fxHint: '1 USD = x CNY',
    syncFx: '同步 FX', syncing: '同步中…', composition: '资产与汇率', positions: '国内基金持仓投影', fund: '基金', shares: '份额', nav: 'NAV', navDate: 'NAV 日期', cnyValue: 'CNY 市值', usdValue: '折合 USD',
    performance: '合并业绩', noCny: '尚无 CNY 定投历史，因此统一报告与原 USD 账户一致。', partial: 'FX 或基金 NAV 不完整时不会猜测折算值，合并结果会降级为 PARTIAL。',
    source: 'USD 真账 + CNY 派生基金 + 历史 USD/CNY',
  } : {
    eyebrow: 'Multi-currency · USD reporting', title: 'Unified asset reporting', subtitle: 'Convert the real USD account and the CNY auto-DCA fund projection into one USD reporting view using historical FX.',
    boundary: 'This is a reporting projection, not a multi-currency cash ledger. CNY funds remain derived from rules + NAV; the real transaction ledger is still USD-only.',
    combinedValue: 'Combined value', combinedPnl: 'Combined P/L', combinedFlow: 'Cumulative external flow', twr: 'Time-weighted return', xirr: 'XIRR', cagr: 'Since-inception CAGR', drawdown: 'Maximum drawdown',
    usdAccount: 'Real USD account', cnyFunds: 'CNY fund value', cnyFundsUsd: 'CNY funds in USD', fundFlow: 'CNY DCA flow in USD', fx: 'USD/CNY', fxHint: '1 USD = x CNY',
    syncFx: 'Sync FX', syncing: 'Syncing…', composition: 'Assets and FX', positions: 'China fund projected positions', fund: 'Fund', shares: 'Shares', nav: 'NAV', navDate: 'NAV date', cnyValue: 'CNY value', usdValue: 'USD value',
    performance: 'Combined performance', noCny: 'There is no CNY DCA history yet, so this report reduces to the existing USD account.', partial: 'Missing FX or fund NAV is never guessed; combined reporting degrades to PARTIAL instead.',
    source: 'Real USD ledger + derived CNY funds + historical USD/CNY',
  }
}

function money(value: string | null | undefined, currency: 'USD' | 'CNY', locale: string) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits: 2 }).format(number)
}

function percent(value: string | null | undefined) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  const sign = number > 0 ? '+' : ''
  return `${sign}${(number * 100).toFixed(2)}%`
}

function number(value: string | null | undefined, digits = 4) {
  if (value == null || value === '') return '—'
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed.toFixed(digits) : '—'
}

function trend(value: string | null | undefined) {
  if (value == null || Number(value) === 0) return 'trend-flat'
  return Number(value) > 0 ? 'trend-positive' : 'trend-negative'
}

export function ReportingPage() {
  const { i18n } = useTranslation()
  const queryClient = useQueryClient()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const locale = isZh ? 'zh-CN' : 'en-US'
  const t = copy(isZh)
  const [range, setRange] = useState<PerformanceRange>('ALL')
  const report = useQuery({
    queryKey: queryKeys.multiCurrencyReport(range),
    queryFn: () => api.getMultiCurrencyReport(range),
    staleTime: 30_000,
  })
  const syncFx = useMutation({
    mutationFn: api.syncUsdCny,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['multi-currency-report'] })
      await report.refetch()
    },
  })

  if (report.isLoading) return <div className="page"><div className="page-intro"><div><span className="page-eyebrow">{t.eyebrow}</span><h1>{t.title}</h1></div></div><LoadingBlock lines={8} /></div>
  if (report.isError || !report.data) return <div className="page"><ErrorState onRetry={() => void report.refetch()} /></div>

  const { summary, performance } = report.data.data
  return <div className="page reporting-page">
    <div className="page-intro">
      <div><span className="page-eyebrow">{t.eyebrow}</span><h1>{t.title}</h1><p>{t.subtitle}</p></div>
      <div className="page-actions"><button type="button" className="button button-secondary" onClick={() => syncFx.mutate()} disabled={syncFx.isPending}><RefreshCw size={15} className={syncFx.isPending ? 'spin-icon' : undefined} />{syncFx.isPending ? t.syncing : t.syncFx}</button></div>
    </div>

    <DataStateBanner status={summary.status} message={summary.status === 'PARTIAL' ? t.partial : t.boundary} source={t.source} asOf={summary.asOf ?? undefined} />

    <div className="reporting-metric-grid">
      <article className="reporting-metric"><small>{t.combinedValue}</small><strong>{money(summary.combinedValueUsd, 'USD', locale)}</strong><span>{t.usdAccount} {money(summary.usdAccountValue, 'USD', locale)}</span></article>
      <article className="reporting-metric"><small>{t.combinedPnl}</small><strong className={trend(summary.combinedPnlUsd)}>{money(summary.combinedPnlUsd, 'USD', locale)}</strong><span>{t.combinedFlow} {money(summary.combinedExternalFlowUsd, 'USD', locale)}</span></article>
      <article className="reporting-metric"><small>{t.twr} · {range}</small><strong className={trend(performance.twr)}>{percent(performance.twr)}</strong><span>{t.cagr} {percent(performance.cagr)}</span></article>
      <article className="reporting-metric"><small>{t.xirr}</small><strong className={trend(performance.xirr)}>{percent(performance.xirr)}</strong><span>{t.drawdown} {percent(performance.maximumDrawdown)}</span></article>
    </div>

    <div className="reporting-grid">
      <Panel title={t.composition} detail={t.boundary}>
        <dl className="reporting-definition-list">
          <div><dt>{t.usdAccount}</dt><dd>{money(summary.usdAccountValue, 'USD', locale)}</dd></div>
          <div><dt>{t.cnyFunds}</dt><dd>{money(summary.cnyFundValue, 'CNY', locale)}</dd></div>
          <div><dt>{t.cnyFundsUsd}</dt><dd>{money(summary.cnyFundValueUsd, 'USD', locale)}</dd></div>
          <div><dt>{t.fundFlow}</dt><dd>{money(summary.cnyAutoDcaExternalFlowUsd, 'USD', locale)}</dd></div>
          <div><dt>{t.fx}</dt><dd>{number(summary.usdCnyRate, 4)} <small>{t.fxHint}{summary.usdCnyRateDate ? ` · ${summary.usdCnyRateDate}` : ''}</small></dd></div>
        </dl>
      </Panel>

      <Panel title={t.performance} action={<div className="segmented-control">{ranges.map((item) => <button type="button" key={item} className={range === item ? 'active' : ''} onClick={() => setRange(item)}>{item}</button>)}</div>}>
        <dl className="reporting-definition-list">
          <div><dt>{t.twr}</dt><dd className={trend(performance.twr)}>{percent(performance.twr)}</dd></div>
          <div><dt>{t.cagr}</dt><dd className={trend(performance.cagr)}>{percent(performance.cagr)}</dd></div>
          <div><dt>{t.xirr}</dt><dd className={trend(performance.xirr)}>{percent(performance.xirr)}</dd></div>
          <div><dt>{t.drawdown}</dt><dd className={trend(performance.maximumDrawdown)}>{percent(performance.maximumDrawdown)}</dd></div>
        </dl>
      </Panel>
    </div>

    <Panel title={t.positions} detail={summary.funds.length === 0 ? t.noCny : `${summary.funds.length}`}>
      {summary.funds.length === 0 ? <p className="empty-copy">{t.noCny}</p> : <div className="table-scroll"><table className="data-table"><thead><tr><th>{t.fund}</th><th>{t.shares}</th><th>{t.nav}</th><th>{t.navDate}</th><th>{t.cnyValue}</th><th>{t.usdValue}</th></tr></thead><tbody>{summary.funds.map((fund) => <tr key={fund.fundCode}><td><strong>{fund.fundCode}</strong><small className="table-subline">{fund.fundName}</small></td><td>{number(fund.shares, 4)}</td><td>{number(fund.nav, 4)}</td><td>{fund.navDate}</td><td>{money(fund.marketValueCny, 'CNY', locale)}</td><td>{money(fund.marketValueUsd, 'USD', locale)}</td></tr>)}</tbody></table></div>}
    </Panel>
  </div>
}
