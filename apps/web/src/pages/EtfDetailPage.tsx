import { lazy, Suspense, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ExternalLink, RefreshCw, Star, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../lib/api'
import { decimal, formatCompactMoney, formatMoney, formatPercent, formatSignedMoney, formatSignedPercent, formatTime } from '../lib/format'
import { invalidateInstrumentHistoryQueries, invalidateInstrumentQueries, queryKeys } from '../lib/queryKeys'
import { ChartRangeTabs } from '../components/ChartRangeTabs'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { MetricCard } from '../components/MetricCard'
import { Panel } from '../components/Panel'

const PriceChart = lazy(async () => ({ default: (await import('../components/charts/PriceChart')).PriceChart }))

const ranges = ['1D', '1W', '1M', '3M', 'YTD', '1Y', '3Y', '5Y', 'ALL']

export function EtfDetailPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { symbol = 'VOO' } = useParams()
  const [range, setRange] = useState('1Y')
  const instrument = useQuery({ queryKey: queryKeys.instrument(symbol), queryFn: () => api.getInstrument(symbol), staleTime: 86_400_000 })
  const quote = useQuery({ queryKey: queryKeys.quote(symbol), queryFn: () => api.getQuote(symbol), staleTime: 60_000 })
  const metrics = useQuery({ queryKey: queryKeys.metrics(symbol), queryFn: () => api.getMetrics(symbol), staleTime: 86_400_000 })
  const prices = useQuery({ queryKey: queryKeys.pricesRange(symbol, range), queryFn: () => api.getPrices(symbol, range), staleTime: 86_400_000 })
  const syncHistory = useMutation({
    mutationFn: () => api.syncInstrument(symbol),
    onSuccess: () => { void invalidateInstrumentHistoryQueries(queryClient, symbol) },
  })
  const untrack = useMutation({ mutationFn: () => api.untrackInstrument(symbol), onSuccess: () => { void invalidateInstrumentQueries(queryClient); void navigate('/etfs') } })

  if (instrument.isError) return <div className="page"><button type="button" className="back-button" onClick={() => navigate('/etfs')}><ArrowLeft size={16} />{t('etfs.title')}</button><ErrorState onRetry={() => void instrument.refetch()} /></div>
  if (instrument.isLoading || !instrument.data) return <div className="page"><LoadingBlock lines={2} /><div className="detail-hero-skeleton"><LoadingBlock lines={5} /></div><div className="metric-grid detail-metrics">{[1, 2, 3, 4].map((item) => <LoadingBlock key={item} lines={2} />)}</div></div>
  const fund = instrument.data.data
  const currentQuote = quote.data?.data
  const currentMetrics = metrics.data?.data
  const nav = currentQuote?.nav ?? fund.nav
  const historyStatus = prices.data?.meta.status ?? syncHistory.data?.data.status ?? (prices.isError ? 'UNAVAILABLE' : 'STALE')
  const historyMessage = prices.data?.meta.message ?? syncHistory.data?.data.message ?? undefined
  const retryHistory = syncHistory.isPending ? undefined : () => syncHistory.mutate()
  return <div className="page etf-detail-page">
    <button type="button" className="back-button" onClick={() => navigate('/etfs')}><ArrowLeft size={16} />{t('etfs.title')}</button>
    <DataStateBanner status={currentQuote?.status ?? quote.data?.meta.status ?? (quote.isError ? 'UNAVAILABLE' : 'STALE')} message={quote.data?.meta.message} source={quote.data?.meta.source === 'FIXTURE' ? t('common.demoData') : quote.data?.meta.source} asOf={quote.data?.meta.asOf} retrievedAt={currentQuote?.retrievedAt ?? quote.data?.meta.retrievedAt} />
    <div className="detail-hero"><div className="detail-identity"><span className="ticker-avatar ticker-avatar-large">{fund.symbol.slice(0, 2)}</span><div><div className="detail-title-row"><h1>{fund.symbol}</h1><span className="watch-star"><Star size={15} fill="currentColor" aria-hidden="true" /></span></div><p>{fund.name}</p><small>{fund.exchange} · {fund.currency} · {fund.issuer}</small></div></div><div className="detail-quote"><strong>{currentQuote ? formatMoney(currentQuote.price) : '—'}</strong><div className={currentQuote && decimal(currentQuote.change).lt(0) ? 'trend-negative' : 'trend-positive'}>{currentQuote ? `${formatSignedMoney(currentQuote.change)} ${formatSignedPercent(currentQuote.changePercent)}` : '—'}</div><small>{t('common.updated')} {currentQuote ? formatTime(currentQuote.retrievedAt) : '—'} ET</small></div><div className="detail-actions"><button type="button" className="button button-ghost" onClick={() => { void quote.refetch(); void metrics.refetch() }}><RefreshCw size={15} />{t('common.refresh')}</button><button type="button" className="icon-button" title={t('etfs.untrack')} aria-label={t('etfs.untrack')} onClick={() => untrack.mutate()} disabled={untrack.isPending}><Trash2 size={16} /></button></div></div>
    <DataStateBanner status={currentMetrics?.dataStatus ?? metrics.data?.meta.status ?? (metrics.isError ? 'UNAVAILABLE' : 'STALE')} message={metrics.data?.meta.message} source={metrics.data?.meta.source} asOf={currentMetrics?.asOf ?? metrics.data?.meta.asOf} retrievedAt={metrics.data?.meta.retrievedAt} />
    <div className="metric-grid detail-metrics"><MetricCard label={t('etfs.oneMonth')} value={formatSignedPercent(currentMetrics?.oneMonth)} tone={currentMetrics?.oneMonth && decimal(currentMetrics.oneMonth).lt(0) ? 'negative' : 'positive'} /><MetricCard label={t('etfs.threeMonths')} value={formatSignedPercent(currentMetrics?.threeMonths)} tone={currentMetrics?.threeMonths && decimal(currentMetrics.threeMonths).lt(0) ? 'negative' : 'positive'} /><MetricCard label={t('etfs.ytd')} value={formatSignedPercent(currentMetrics?.ytd)} tone={currentMetrics?.ytd && decimal(currentMetrics.ytd).lt(0) ? 'negative' : 'positive'} /><MetricCard label={t('etfs.oneYear')} value={formatSignedPercent(currentMetrics?.oneYear)} tone={currentMetrics?.oneYear && decimal(currentMetrics.oneYear).lt(0) ? 'negative' : 'positive'} /><MetricCard label={t('etfs.threeYearCagr')} value={formatSignedPercent(currentMetrics?.threeYearCagr)} tone="accent" /><MetricCard label={t('etfs.currentDrawdown')} value={formatSignedPercent(currentMetrics?.currentDrawdown)} tone={currentMetrics?.currentDrawdown && decimal(currentMetrics.currentDrawdown).lt(0) ? 'warning' : 'positive'} /></div>
    <div className="content-grid detail-content-grid"><Panel className="detail-chart-panel" title={t('etfs.history')} detail={t('etfs.adjustedHistory')} action={<ChartRangeTabs ranges={ranges} value={range} onChange={setRange} />}><DataStateBanner status={historyStatus} message={historyMessage} source={prices.data?.meta.source === 'FIXTURE' ? t('common.demoData') : prices.data?.meta.source} asOf={prices.data?.meta.asOf} retrievedAt={prices.data?.meta.retrievedAt} onRetry={historyStatus === 'FRESH' ? undefined : retryHistory} />{prices.isLoading ? <LoadingBlock lines={5} /> : prices.isError ? <ErrorState onRetry={() => void prices.refetch()} /> : prices.data?.data.length ? <Suspense fallback={<div className="chart-loading" aria-label={t('common.loading')} />}><PriceChart data={prices.data.data} /></Suspense> : <EmptyState title={t('common.noData')} detail={historyMessage ?? t('etfs.historyUnavailable')} action={<button type="button" className="button button-secondary button-small" onClick={() => { void syncHistory.mutate() }} disabled={syncHistory.isPending}><RefreshCw size={14} />{t('common.retry')}</button>} />}</Panel><Panel title={t('etfs.details')} detail={t('etfs.fundMetadata')}><div className="fund-detail-list"><div><span>{t('etfs.expenseRatio')}</span><strong>{fund.expenseRatio ? formatPercent(fund.expenseRatio) : '—'}</strong></div><div><span>{t('etfs.aum')}</span><strong>{fund.aum ? formatCompactMoney(fund.aum) : '—'}</strong></div><div><span>{t('etfs.dividendYield')}</span><strong>{fund.dividendYield ? formatPercent(fund.dividendYield) : '—'}</strong></div><div><span>{t('etfs.nav')}</span><strong>{nav ? formatMoney(nav) : '—'}</strong></div><div><span>{t('etfs.fiftyTwoWeekHigh')}</span><strong>{currentMetrics?.fiftyTwoWeekHigh ? formatMoney(currentMetrics.fiftyTwoWeekHigh) : '—'}</strong></div><div><span>{t('etfs.fiftyTwoWeekLow')}</span><strong>{currentMetrics?.fiftyTwoWeekLow ? formatMoney(currentMetrics.fiftyTwoWeekLow) : '—'}</strong></div><div><span>{t('etfs.maxDrawdown')}</span><strong className="text-negative">{formatSignedPercent(currentMetrics?.maxDrawdown1Y)}</strong></div><div><span>{t('etfs.dataSource')}</span><strong>{quote.data?.meta.source ?? currentQuote?.source ?? '—'}</strong></div></div><div className="fund-note"><ExternalLink size={14} /><span>{t('etfs.performanceNote')}</span></div></Panel></div>
    <Link to="/transactions" className="detail-inline-link">{t('etfs.addTransactionFor', { symbol: fund.symbol })} <ExternalLink size={14} /></Link>
  </div>
}
