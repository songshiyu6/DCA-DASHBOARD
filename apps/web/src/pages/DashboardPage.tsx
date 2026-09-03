import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { ArrowUpRight, CalendarDays, ChevronRight, CircleDollarSign, Download, RefreshCw, TrendingUp } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { annualizedTimeWeightedReturn, CHART_RANGE_OPTIONS, filterPortfolioHistory, livePerformanceSincePreviousTradingClose, marketBusinessDay, portfolioRangeStartDay, withCurrentPortfolioPoint, ytdPerformance, type ChartRange } from '../lib/dashboardPerformance'
import { decimal, decimalMax, decimalMin, formatDate, formatMoney, formatPeriod, formatShares, formatSignedMoney, formatSignedPercent, formatPercent, formatTime } from '../lib/format'
import { isInitialContributionPeriod } from '../lib/initialContributionPresentation'
import { queryKeys } from '../lib/queryKeys'
import { quoteSessionLabel } from '../lib/quotePresentation'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { MetricCard } from '../components/MetricCard'
import { Panel } from '../components/Panel'
import type { Holding, Quote, RecommendationItem } from '../types'

const PortfolioChart = lazy(async () => ({ default: (await import('../components/charts/PortfolioChart')).PortfolioChart }))
const PortfolioPerformancePanel = lazy(async () => ({ default: (await import('../components/charts/PortfolioPerformancePanel')).PortfolioPerformancePanel }))

function trendClass(value: string | null | undefined): string {
  if (!value || decimal(value).isZero()) return 'trend-flat'
  return decimal(value).gt(0) ? 'trend-positive' : 'trend-negative'
}

function ContributionBars({ months, planStartDate, initialPrincipal, initialLabel }: { months: Array<{ period: string; planned: string; executed: string; status: string }>; planStartDate?: string; initialPrincipal: string; initialLabel: string }) {
  return <div className="contribution-months">{months.map((month) => {
    const initial = isInitialContributionPeriod(month.period, month.status, planStartDate, initialPrincipal, month.executed)
    const planned = decimal(month.planned)
    const ratio = planned.gt(0) ? decimalMin(decimalMax(decimal(month.executed).div(planned), 0), 1).toNumber() : 0
    const barStatus = initial ? 'initial' : month.status.toLowerCase()
    const title = initial
      ? `${formatPeriod(month.period)} · ${initialLabel} ${formatMoney(initialPrincipal)}`
      : `${formatPeriod(month.period)} · ${formatMoney(month.executed)} / ${formatMoney(month.planned)}`
    const height = initial ? 100 : Math.max(ratio * 100, month.status === 'UPCOMING' ? 4 : 8)
    return <div className="month-cell" key={month.period} title={title}><div className={`month-bar month-${barStatus}`}><span style={{ height: `${height}%` }} /></div><small>{month.period.slice(5)}</small></div>
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

function HoldingRow({ holding, quote, onOpen }: { holding: Holding; quote?: Quote; onOpen: (symbol: string) => void }) {
  const { t, i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const quoteDetail = quote
    ? `${quoteSessionLabel(quote.quoteSession, isZh)} · ${formatTime(quote.marketTimestamp)} ET`
    : null
  return <button type="button" className="holding-row" onClick={() => onOpen(holding.symbol)} aria-label={t('dashboard.openHolding', { symbol: holding.symbol })}>
    <span className="holding-identity"><span className="ticker-avatar">{holding.symbol.slice(0, 1)}</span><span><strong>{holding.symbol}</strong><small>{holding.name}</small></span></span>
    <span className="holding-price"><strong>{formatMoney(holding.price)}</strong><small className={trendClass(holding.todayPercent)}>{formatSignedPercent(holding.todayPercent)}</small>{quoteDetail ? <small>{quoteDetail}</small> : null}</span>
    <span className="holding-shares"><strong>{formatShares(holding.shares)}</strong><small>{t('dashboard.shares')} · {t('dashboard.avg')} {formatMoney(holding.avgCost)}</small></span>
    <span className="holding-value"><strong>{formatMoney(holding.marketValue)}</strong><small>{formatPercent(holding.allocation)} {t('dashboard.allocation').toLowerCase()}</small></span>
    <span className={`holding-pnl ${trendClass(holding.unrealizedPnl)}`}><strong>{formatSignedMoney(holding.unrealizedPnl)}</strong><small>{formatSignedPercent(holding.returnPercent)}</small></span>
    <ChevronRight size={16} className="holding-chevron" />
  </button>
}

export function DashboardPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const dashboard = useQuery({ queryKey: queryKeys.dashboard, queryFn: api.getDashboard, staleTime: 30_000 })
  const plans = useQuery({ queryKey: queryKeys.plans, queryFn: api.getPlans })
  const activePlan = plans.data?.data.find((candidate) => candidate.status === 'ACTIVE')
  const contributionAnalysis = useQuery({ queryKey: activePlan ? queryKeys.contributionAnalysis(activePlan.id) : queryKeys.contributionAnalysis('none'), queryFn: () => api.getContributionAnalysis(activePlan?.id ?? ''), enabled: Boolean(activePlan) })
  const [chartRange, setChartRange] = useState<ChartRange>('1Y')
  const [marketRefreshing, setMarketRefreshing] = useState(false)
  const refreshInFlight = useRef(false)
  const initialRefreshDone = useRef(false)
  const rawData = dashboard.data?.data
  const symbols = useMemo(() => rawData?.holdings.map((holding) => holding.symbol).filter(Boolean) ?? [], [rawData?.holdings])
  const quotes = useQueries({ queries: symbols.map((symbol) => ({
    queryKey: queryKeys.quote(symbol),
    queryFn: () => api.getQuote(symbol),
    staleTime: 60_000,
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: 'always' as const,
  })) })
  const quoteBySymbol = useMemo(() => Object.fromEntries(quotes.map((query, index) => [symbols[index], query.data?.data])), [quotes, symbols])
  const regularHistory = useMemo(() => rawData?.portfolioHistory ?? [], [rawData?.portfolioHistory])
  const livePerformanceHistory = useMemo(() => withCurrentPortfolioPoint(
    regularHistory,
    rawData?.summary.marketValue,
    rawData?.summary.netInvested,
    dashboard.data?.meta.retrievedAt,
    dashboard.data?.meta.status ?? 'STALE',
  ), [regularHistory, rawData?.summary.marketValue, rawData?.summary.netInvested, dashboard.data?.meta.retrievedAt, dashboard.data?.meta.status])
  const visibleHistory = useMemo(() => filterPortfolioHistory(regularHistory, chartRange), [regularHistory, chartRange])
  const cagr = useMemo(() => annualizedTimeWeightedReturn(regularHistory), [regularHistory])
  const chartRangeEnd = regularHistory.at(-1)?.date.slice(0, 10)
  const chartRangeStart = chartRangeEnd ? portfolioRangeStartDay(chartRangeEnd, chartRange) ?? undefined : undefined
  const currentBusinessDay = useMemo(
    () => marketBusinessDay(dashboard.data?.meta.asOf ?? dashboard.data?.meta.retrievedAt),
    [dashboard.data?.meta.asOf, dashboard.data?.meta.retrievedAt],
  )
  const today = useMemo(() => livePerformanceSincePreviousTradingClose(
    regularHistory,
    currentBusinessDay,
    rawData?.summary.marketValue,
    rawData?.summary.netInvested,
  ), [regularHistory, currentBusinessDay, rawData?.summary.marketValue, rawData?.summary.netInvested])
  const regularYtd = useMemo(() => ytdPerformance(regularHistory), [regularHistory])
  const liveYtd = useMemo(() => ytdPerformance(livePerformanceHistory), [livePerformanceHistory])
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const portfolioLabel = isZh ? '投资组合' : 'Portfolio'
  const portfolioOverviewLabel = isZh ? '投资组合总览' : 'Portfolio overview'
  const cumulativePnlLabel = isZh ? '累计盈亏' : 'Cumulative P/L'
  const longTermPerformanceLabel = isZh ? '长期表现' : 'Long-term performance'
  const longTermPerformanceDetail = isZh ? '策略与资金回报' : 'Strategy and money-weighted returns'
  const timeWeightedAnnualizedLabel = isZh ? '时间加权年化' : 'Time-weighted annualized'
  const netLiqLabel = isZh ? '净清算价值' : 'Net Liq Value'
  const netInvestmentLabel = isZh ? '净投入' : 'Net investment'
  const portfolioGrowthLabel = isZh ? '投资组合增长' : 'Portfolio growth'
  const portfolioGrowthDetail = isZh ? '净清算价值与净投入对比' : 'Net liq value versus net investment'
  const initialLabel = isZh ? '初始投入' : 'Initial capital'
  const initialPrincipal = contributionAnalysis.data?.data.initial.principal ?? '0'
  const initialPeriod = activePlan?.startDate.slice(0, 7)

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

  if (dashboard.isLoading) return <div className="page"><div className="page-intro"><div><span className="page-eyebrow">{portfolioOverviewLabel}</span><h1>{t('dashboard.title')}</h1></div></div><div className="metric-grid metric-grid-main">{[1, 2, 3, 4].map((item) => <LoadingBlock key={item} lines={2} />)}</div><div className="content-grid"><Panel title={portfolioGrowthLabel}><LoadingBlock lines={5} /></Panel><Panel title={t('dashboard.nextDca')}><LoadingBlock lines={4} /></Panel></div></div>
  if (dashboard.isError || !dashboard.data) return <div className="page"><ErrorState onRetry={() => void dashboard.refetch()} /></div>
  const { data, meta } = dashboard.data
  const summary = data.summary
  const progress = data.contributionProgress
  const cumulativeReturn = summary.totalPnl !== null && decimal(summary.netInvested).gt(0)
    ? decimal(summary.totalPnl).div(summary.netInvested).toString()
    : null
  const ytdPnl = liveYtd.pnl ?? (regularHistory.length && regularHistory[0].date.slice(0, 4) === regularHistory[regularHistory.length - 1].date.slice(0, 4)
    ? summary.totalPnl
    : null)
  const showInitialProgress = Boolean(progress && initialPeriod && progress.months.some((month) => isInitialContributionPeriod(month.period, month.status, activePlan?.startDate, initialPrincipal, month.executed)))
  return <div className="page dashboard-page dashboard-page-v2">
    <div className="page-intro dashboard-intro"><div><span className="page-eyebrow">{portfolioOverviewLabel}</span><h1>{t('dashboard.title')}</h1><p>{t('dashboard.subtitle')}</p></div><div className="page-actions"><button type="button" className="button button-ghost" onClick={() => void refreshMarket()} disabled={marketRefreshing || dashboard.isFetching}><RefreshCw size={15} className={marketRefreshing ? 'spin-icon' : undefined} />{marketRefreshing || dashboard.isFetching ? t('common.loading') : t('common.refresh')}</button><button type="button" className="button button-secondary" onClick={() => { exportDashboard(data) }}><Download size={15} />{t('common.export')}</button></div></div>
    <DataStateBanner status={meta.status} message={meta.message} source={meta.source === 'FIXTURE' ? t('common.demoData') : meta.source} asOf={meta.asOf} retrievedAt={meta.retrievedAt} />
    <div className="dashboard-summary-grid" aria-label={portfolioLabel}>
      <article className="portfolio-summary-card">
        <div className="portfolio-summary-top"><span className="metric-label">{portfolioLabel}</span><span className="metric-icon"><CircleDollarSign size={15} strokeWidth={1.8} /></span></div>
        <strong className="portfolio-summary-value">{formatMoney(summary.marketValue)}</strong>
        <div className="portfolio-summary-meta">
          <span><small>{netInvestmentLabel}</small><strong>{formatMoney(summary.netInvested)}</strong></span>
          <span><small>{cumulativePnlLabel}</small><strong className={trendClass(summary.totalPnl)}>{formatSignedMoney(summary.totalPnl)} <em>{formatSignedPercent(cumulativeReturn)}</em></strong></span>
        </div>
      </article>
      <MetricCard className="dashboard-period-card" label={t('common.today')} value={formatSignedMoney(today.pnl)} detail={formatSignedPercent(today.returnRate)} icon={TrendingUp} tone={trendClass(today.pnl) === 'trend-negative' ? 'negative' : 'positive'} />
      <MetricCard className="dashboard-period-card" label="YTD" value={formatSignedMoney(ytdPnl)} detail={formatSignedPercent(regularYtd.returnRate)} icon={ArrowUpRight} tone={trendClass(ytdPnl) === 'trend-negative' ? 'negative' : 'positive'} />
    </div>
    <div className="dashboard-long-term-strip" aria-label={`${longTermPerformanceLabel}, CAGR, XIRR`}>
      <span className="dashboard-long-term-heading"><small>{longTermPerformanceLabel}</small><span>{longTermPerformanceDetail}</span></span>
      <span className="dashboard-long-term-metric"><small>CAGR</small><strong className={trendClass(cagr)}>{formatSignedPercent(cagr)}</strong><em>{timeWeightedAnnualizedLabel}</em></span>
      <span className="dashboard-long-term-metric"><small>XIRR</small><strong className={trendClass(summary.xirr)}>{formatSignedPercent(summary.xirr)}</strong><em>{t('dashboard.moneyWeightedReturn')}</em></span>
    </div>
    <Panel title={t('dashboard.holdings')} detail={t('dashboard.ledgerProjection')} action={<button type="button" className="text-button" onClick={() => navigate('/transactions')}>{t('common.viewAll')} <ChevronRight size={15} /></button>} className="holdings-panel dashboard-holdings-first" flush>
      {data.holdings.length ? <div className="holdings-list">{data.holdings.map((holding) => <HoldingRow key={holding.symbol} holding={holding} quote={quoteBySymbol[holding.symbol]} onOpen={(symbol) => navigate(`/etfs/${symbol}`)} />)}</div> : <EmptyState title={t('common.noData')} />}
    </Panel>
    <div className="content-grid dashboard-top-grid dashboard-primary-grid">
      <Panel className="chart-panel" title={portfolioGrowthLabel} detail={portfolioGrowthDetail} action={<div className="chart-toolbar"><div className="chart-legend"><span><i className="legend-dot legend-market" />{netLiqLabel}</span><span><i className="legend-dot legend-investment" />{netInvestmentLabel}</span></div><div className="chart-range-control" aria-label={t('charts.range')}>{CHART_RANGE_OPTIONS.map((range) => <button key={range} type="button" className={chartRange === range ? 'active' : ''} aria-pressed={chartRange === range} onClick={() => setChartRange(range)}>{range}</button>)}</div></div>}>
        {visibleHistory.length ? <Suspense fallback={<div className="chart-loading" aria-label={t('common.loading')} />}><PortfolioChart data={visibleHistory} netLiqLabel={netLiqLabel} netInvestmentLabel={netInvestmentLabel} rangeStart={chartRangeStart} rangeEnd={chartRangeEnd} /></Suspense> : <EmptyState title={t('common.noData')} detail={t('dashboard.historyEmpty')} />}
      </Panel>
      {data.nextDca ? <NextDcaCard {...data.nextDca} /> : <Panel title={t('dashboard.nextDca')}><EmptyState title={t('plan.noPlan')} /></Panel>}
    </div>
    <Suspense fallback={<Panel title={isZh ? '投资表现' : 'Investment performance'}><LoadingBlock lines={5} /></Panel>}>
      <PortfolioPerformancePanel history={regularHistory} />
    </Suspense>
    <div className="content-grid dashboard-mid-grid dashboard-secondary-grid">
      <Panel title={t('dashboard.allocation')} detail={t('dashboard.targetVsActual')} className="allocation-panel">
        {data.allocation.length ? <div className="allocation-list">{data.allocation.map((row) => <div className="allocation-item" key={row.symbol}><div className="allocation-item-head"><strong>{row.symbol}</strong><span className={trendClass(row.drift)}>{formatSignedPercent(row.drift)}</span></div><div className="allocation-track"><span className="allocation-target" style={{ width: `${decimal(row.targetWeight).mul(100).toNumber()}%` }} /><span className="allocation-actual" style={{ width: `${decimal(row.actualWeight).mul(100).toNumber()}%` }} /></div><div className="allocation-item-meta"><span>{t('dashboard.target')} {formatPercent(row.targetWeight)}</span><span>{t('dashboard.actual')} {formatPercent(row.actualWeight)}</span><span>{formatMoney(row.marketValue)}</span></div></div>)}</div> : <EmptyState title={t('common.noData')} />}
        <div className="allocation-key"><span><i className="key-line key-target" />{t('dashboard.target')}</span><span><i className="key-line key-actual" />{t('dashboard.actual')}</span></div>
      </Panel>
      {progress ? <Panel title={t('dashboard.dcaProgress')} detail={t('dashboard.contributionProgress', { year: progress.year })} action={<div className="progress-summary"><strong>{formatMoney(progress.executed)}</strong><span>/ {formatMoney(progress.planned)}</span><b>{formatPercent(progress.executionRate)}</b></div>}>
        <ContributionBars months={progress.months} planStartDate={activePlan?.startDate} initialPrincipal={initialPrincipal} initialLabel={initialLabel} />
        {showInitialProgress ? <div className="progress-initial-row"><span><b>{formatPeriod(initialPeriod ?? '')}</b><small>{initialLabel}</small></span><strong>{formatMoney(initialPrincipal)}</strong></div> : null}
        <div className="progress-foot"><span>{t('dashboard.executed')} {formatMoney(progress.executed)}</span><span>{t('dashboard.remaining')} {formatMoney(decimalMax(decimal(progress.planned).minus(progress.executed), 0).toString())}</span></div>
      </Panel> : <Panel title={t('dashboard.dcaProgress')}><EmptyState title={t('plan.noPlan')} detail={t('plan.createPlan')} /></Panel>}
    </div>
    <p className="data-footnote">{t('dashboard.performanceFootnote', { date: formatDate(meta.asOf) })}</p>
  </div>
}
