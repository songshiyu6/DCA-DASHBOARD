import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowUpRight, CalendarDays, ChevronRight, CircleDollarSign, Download, RefreshCw, TrendingUp } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { filterPortfolioHistory, latestDayPerformance, withCurrentPortfolioPoint, ytdTimeWeightedReturn, type ChartRange } from '../lib/dashboardPerformance'
import { decimal, decimalMax, decimalMin, formatDate, formatMoney, formatPeriod, formatShares, formatSignedMoney, formatSignedPercent, formatPercent } from '../lib/format'
import { queryKeys } from '../lib/queryKeys'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { MetricCard } from '../components/MetricCard'
import { Panel } from '../components/Panel'
import type { Holding, RecommendationItem } from '../types'

const PortfolioChart = lazy(async () => ({ default: (await import('../components/charts/PortfolioChart')).PortfolioChart }))

function trendClass(value: string | null | undefined): string {
  if (!value || decimal(value).isZero()) return 'trend-flat'
  return decimal(value).gt(0) ? 'trend-positive' : 'trend-negative'
}

function ContributionBars({ months }: { months: Array<{ period: string; planned: string; executed: string; status: string }> }) {
  return <div className="contribution-months">{months.map((month) => {
    const planned = decimal(month.planned)
    const ratio = planned.gt(0) ? decimalMin(decimalMax(decimal(month.executed).div(planned), 0), 1).toNumber() : 0
    return <div className="month-cell" key={month.period} title={`${formatPeriod(month.period)} · ${formatMoney(month.executed)} / ${formatMoney(month.planned)}`}><div className={`month-bar month-${month.status.toLowerCase()}`}><span style={{ height: `${Math.max(ratio * 100, month.status === 'UPCOMING' ? 4 : 8)}%` }} /></div><small>{month.period.slice(5)}</small></div>
  })}</div>
}

function NextDcaCard({ amount, period, daysRemaining, items, dataStatus, message }: { amount: string; period: string; daysRemaining: number; items: RecommendationItem[]; dataStatus?: import('../types').DataStatus; message?: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return <Panel className="next-dca-panel" title={t('dashboard.nextDca')} detail={formatPeriod(period)} action={<CalendarDays size={16} className="panel-icon" />}>
    {dataStatus ? <DataStateBanner status={dataStatus} message={message} /> : null}
    <div className="next-dca-amount"><strong>{formatMoney(amount)}</strong><span>{t('dashboard.daysRemaining', { count: daysRemaining })}</span></div>
    <div className="recommendation-list compact-list">{items.map((item) => <div className="recommendation-row" key={item.symbol}><span className="ticker-chip">{item.symbol}</span><span className="recommendation-line" /><strong>{formatMoney(item.suggestedAmount)}</strong></div>)}</div>
    <button type="button" className="text-button full-width-button" onClick={() => navigate('/plan')}>{t('dashboard.reviewAllocation')} <ChevronRight size={15} /></button>
  </Panel>
}

function HoldingRow({ holding, onOpen }: { holding: Holding; onOpen: (symbol: string) => void }) {
  const { t } = useTranslation()
  return <button type="button" className="holding-row" onClick={() => onOpen(holding.symbol)} aria-label={t('dashboard.openHolding', { symbol: holding.symbol })}>
    <span className="holding-identity"><span className="ticker-avatar">{holding.symbol.slice(0, 1)}</span><span><strong>{holding.symbol}</strong><small>{holding.name}</small></span></span>
    <span className="holding-price"><strong>{formatMoney(holding.price)}</strong><small className={trendClass(holding.todayPercent)}>{formatSignedPercent(holding.todayPercent)}</small></span>
    <span className="holding-shares"><strong>{formatShares(holding.shares)}</strong><small>{t('dashboard.shares')} · {t('dashboard.avg')} {formatMoney(holding.avgCost)}</small></span>
    <span className="holding-value"><strong>{formatMoney(holding.marketValue)}</strong><small>{formatPercent(holding.allocation)} {t('dashboard.allocation').toLowerCase()}</small></span>
    <span className={`holding-pnl ${trendClass(holding.unrealizedPnl)}`}><strong>{formatSignedMoney(holding.unrealizedPnl)}</strong><small>{formatSignedPercent(holding.returnPercent)}</small></span>
    <ChevronRight size={16} className="holding-chevron" />
  </button>
}

export function DashboardPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const dashboard = useQuery({ queryKey: queryKeys.dashboard, queryFn: api.getDashboard, staleTime: 30_000 })
  const [chartRange, setChartRange] = useState<ChartRange>('1Y')
  const [marketRefreshing, setMarketRefreshing] = useState(false)
  const refreshInFlight = useRef(false)
  const initialRefreshDone = useRef(false)
  const rawData = dashboard.data?.data
  const symbols = useMemo(() => rawData?.holdings.map((holding) => holding.symbol).filter(Boolean) ?? [], [rawData?.holdings])
  const currentHistory = useMemo(() => withCurrentPortfolioPoint(
    rawData?.portfolioHistory ?? [],
    rawData?.summary.marketValue,
    rawData?.summary.netInvested,
    dashboard.data?.meta.retrievedAt,
    dashboard.data?.meta.status ?? 'STALE',
  ), [rawData?.portfolioHistory, rawData?.summary.marketValue, rawData?.summary.netInvested, dashboard.data?.meta.retrievedAt, dashboard.data?.meta.status])
  const visibleHistory = useMemo(() => filterPortfolioHistory(currentHistory, chartRange), [currentHistory, chartRange])
  const today = useMemo(() => latestDayPerformance(currentHistory), [currentHistory])
  const ytd = useMemo(() => ytdTimeWeightedReturn(currentHistory), [currentHistory])

  const refreshMarket = useCallback(async () => {
    if (refreshInFlight.current) return
    refreshInFlight.current = true
    setMarketRefreshing(true)
    try {
      if (symbols.length) await Promise.allSettled(symbols.map((symbol) => api.getQuote(symbol)))
      await dashboard.refetch()
    } finally {
      refreshInFlight.current = false
      setMarketRefreshing(false)
    }
  }, [dashboard, symbols])

  useEffect(() => {
    if (!dashboard.data || !symbols.length || initialRefreshDone.current) return
    initialRefreshDone.current = true
    void refreshMarket()
  }, [dashboard.data, refreshMarket, symbols.length])

  useEffect(() => {
    if (!symbols.length) return
    const refreshIfVisible = () => {
      if (document.visibilityState === 'visible') void refreshMarket()
    }
    const interval = window.setInterval(refreshIfVisible, 60_000)
    document.addEventListener('visibilitychange', refreshIfVisible)
    return () => {
      window.clearInterval(interval)
      document.removeEventListener('visibilitychange', refreshIfVisible)
    }
  }, [refreshMarket, symbols.length])

  const exportDashboard = (data: import('../types').DashboardData) => {
    const rows = [['date', 'marketValue', 'netInvested'], ...data.portfolioHistory.map((point) => [point.date, point.marketValue, point.netInvested])]
    const csv = rows.map((row) => row.join(',')).join('\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'dca-terminal-portfolio-history.csv'
    link.click()
    URL.revokeObjectURL(url)
  }

  if (dashboard.isLoading) return <div className="page"><div className="page-intro"><div><span className="page-eyebrow">{t('dashboard.eyebrow')}</span><h1>{t('dashboard.title')}</h1></div></div><div className="metric-grid metric-grid-main">{[1, 2, 3, 4].map((item) => <LoadingBlock key={item} lines={2} />)}</div><div className="content-grid"><Panel title={t('dashboard.portfolioGrowth')}><LoadingBlock lines={5} /></Panel><Panel title={t('dashboard.nextDca')}><LoadingBlock lines={4} /></Panel></div></div>
  if (dashboard.isError || !dashboard.data) return <div className="page"><ErrorState onRetry={() => void dashboard.refetch()} /></div>
  const { data, meta } = dashboard.data
  const summary = data.summary
  const progress = data.contributionProgress
  const cumulativeReturn = summary.totalPnl !== null && decimal(summary.netInvested).gt(0)
    ? decimal(summary.totalPnl).div(summary.netInvested).toString()
    : null
  return <div className="page dashboard-page dashboard-page-v2">
    <div className="page-intro dashboard-intro"><div><span className="page-eyebrow">{t('dashboard.eyebrow')}</span><h1>{t('dashboard.title')}</h1><p>{t('dashboard.subtitle')}</p></div><div className="page-actions"><button type="button" className="button button-ghost" onClick={() => void refreshMarket()} disabled={marketRefreshing || dashboard.isFetching}><RefreshCw size={15} className={marketRefreshing ? 'spin-icon' : undefined} />{marketRefreshing || dashboard.isFetching ? t('common.loading') : t('common.refresh')}</button><button type="button" className="button button-secondary" onClick={() => { exportDashboard(data) }}><Download size={15} />{t('common.export')}</button></div></div>
    <DataStateBanner status={meta.status} message={meta.message} source={meta.source === 'FIXTURE' ? t('common.demoData') : meta.source} asOf={meta.asOf} retrievedAt={meta.retrievedAt} />
    <div className="metric-grid metric-grid-main dashboard-performance-grid">
      <MetricCard label={t('dashboard.portfolio')} value={formatMoney(summary.marketValue)} detail={t('dashboard.trackedEtfs', { count: data.holdings.length })} icon={CircleDollarSign} tone="accent" />
      <MetricCard label={`${t('common.today')} ${t('dashboard.return')}`} value={formatSignedMoney(today.pnl)} detail={formatSignedPercent(today.returnRate)} icon={TrendingUp} tone={trendClass(today.pnl) === 'trend-negative' ? 'negative' : 'positive'} />
      <MetricCard label={t('dashboard.totalPnl')} value={formatSignedMoney(summary.totalPnl)} detail={`${formatSignedPercent(cumulativeReturn)} ${t('dashboard.sinceInception')}`} icon={TrendingUp} tone={trendClass(summary.totalPnl) === 'trend-negative' ? 'negative' : 'positive'} />
      <MetricCard label={t('etfs.ytd')} value={formatSignedPercent(ytd)} detail={t('dashboard.return')} icon={ArrowUpRight} tone={trendClass(ytd) === 'trend-negative' ? 'negative' : 'positive'} />
    </div>
    <div className="capital-summary-strip" aria-label={t('dashboard.netInvested')}>
      <span><small>{t('dashboard.netInvested')}</small><strong>{formatMoney(summary.netInvested)}</strong></span>
      <span><small>{t('dashboard.remainingCostBasis')}</small><strong>{formatMoney(summary.costBasis)}</strong></span>
      <span><small>{t('dashboard.personalXirr')}</small><strong className={trendClass(summary.xirr)}>{formatSignedPercent(summary.xirr)}</strong><em>{t('dashboard.moneyWeightedReturn')}</em></span>
    </div>
    <Panel title={t('dashboard.holdings')} detail={t('dashboard.ledgerProjection')} action={<button type="button" className="text-button" onClick={() => navigate('/transactions')}>{t('common.viewAll')} <ChevronRight size={15} /></button>} className="holdings-panel dashboard-holdings-first" flush>
      {data.holdings.length ? <div className="holdings-list">{data.holdings.map((holding) => <HoldingRow key={holding.symbol} holding={holding} onOpen={(symbol) => navigate(`/etfs/${symbol}`)} />)}</div> : <EmptyState title={t('common.noData')} />}
    </Panel>
    <div className="content-grid dashboard-top-grid dashboard-primary-grid">
      <Panel className="chart-panel" title={t('dashboard.portfolioGrowth')} detail={t('dashboard.marketValueVsContributions')} action={<div className="chart-toolbar"><div className="chart-legend"><span><i className="legend-dot legend-market" />{t('dashboard.marketValue')}</span><span><i className="legend-dot legend-investment" />{t('dashboard.contribution')}</span></div><div className="chart-range-control" aria-label={t('charts.range')}>{(['1M', '3M', 'YTD', '1Y'] as ChartRange[]).map((range) => <button key={range} type="button" className={chartRange === range ? 'active' : ''} onClick={() => setChartRange(range)}>{range}</button>)}</div></div>}>
        {visibleHistory.length ? <Suspense fallback={<div className="chart-loading" aria-label={t('common.loading')} />}><PortfolioChart data={visibleHistory} /></Suspense> : <EmptyState title={t('common.noData')} detail={t('dashboard.historyEmpty')} />}
      </Panel>
      {data.nextDca ? <NextDcaCard {...data.nextDca} /> : <Panel title={t('dashboard.nextDca')}><EmptyState title={t('plan.noPlan')} /></Panel>}
    </div>
    <div className="content-grid dashboard-mid-grid dashboard-secondary-grid">
      <Panel title={t('dashboard.allocation')} detail={t('dashboard.targetVsActual')} className="allocation-panel">
        {data.allocation.length ? <div className="allocation-list">{data.allocation.map((row) => <div className="allocation-item" key={row.symbol}><div className="allocation-item-head"><strong>{row.symbol}</strong><span className={trendClass(row.drift)}>{formatSignedPercent(row.drift)}</span></div><div className="allocation-track"><span className="allocation-target" style={{ width: `${decimal(row.targetWeight).mul(100).toNumber()}%` }} /><span className="allocation-actual" style={{ width: `${decimal(row.actualWeight).mul(100).toNumber()}%` }} /></div><div className="allocation-item-meta"><span>{t('dashboard.target')} {formatPercent(row.targetWeight)}</span><span>{t('dashboard.actual')} {formatPercent(row.actualWeight)}</span><span>{formatMoney(row.marketValue)}</span></div></div>)}</div> : <EmptyState title={t('common.noData')} />}
        <div className="allocation-key"><span><i className="key-line key-target" />{t('dashboard.target')}</span><span><i className="key-line key-actual" />{t('dashboard.actual')}</span></div>
      </Panel>
      {progress ? <Panel title={t('dashboard.dcaProgress')} detail={t('dashboard.contributionProgress', { year: progress.year })} action={<div className="progress-summary"><strong>{formatMoney(progress.executed)}</strong><span>/ {formatMoney(progress.planned)}</span><b>{formatPercent(progress.executionRate)}</b></div>}>
        <ContributionBars months={progress.months} />
        <div className="progress-foot"><span>{t('dashboard.executed')} {formatMoney(progress.executed)}</span><span>{t('dashboard.remaining')} {formatMoney(decimalMax(decimal(progress.planned).minus(progress.executed), 0).toString())}</span></div>
      </Panel> : <Panel title={t('dashboard.dcaProgress')}><EmptyState title={t('plan.noPlan')} detail={t('plan.createPlan')} /></Panel>}
    </div>
    <p className="data-footnote">{t('dashboard.performanceFootnote', { date: formatDate(meta.asOf) })}</p>
  </div>
}
