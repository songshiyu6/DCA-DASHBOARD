import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Eye, EyeOff, Plus, Search, X } from 'lucide-react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useTranslation } from 'react-i18next'
import { benchmarksApi } from '../../lib/api/benchmarks'
import type { BenchmarkSearchResult } from '../../lib/benchmarkTypes'
import { CHART_RANGE_OPTIONS, latestFreshPortfolioHistoryPoint, portfolioRangeStartDay, timeWeightedReturn, type ChartRange } from '../../lib/dashboardPerformance'
import { formatSignedPercent } from '../../lib/format'
import { benchmarkPriceLevels, portfolioTwrLevels, rebasePerformanceLines, type PerformanceLine } from '../../lib/performanceSeries'
import type { PortfolioHistoryPoint } from '../../types'
import { EmptyState } from '../DataState'
import { Panel } from '../Panel'
import './portfolio-performance.css'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

const STORAGE_KEY = 'dca-performance-benchmarks-v1'
const SEARCH_DEBOUNCE_MS = 300
const BENCHMARK_STALE_REFETCH_MS = 5 * 60_000
const BENCHMARK_FRESH_REFETCH_MS = 15 * 60_000
const PERFORMANCE_RANGE_OPTIONS = [...CHART_RANGE_OPTIONS, 'ALL'] as const

type PerformanceRange = ChartRange | 'ALL'

export interface PerformanceSummaryMetrics {
  twr?: string | null
  cagr?: string | null
  xirr?: string | null
  maxDrawdown?: string | null
}

interface PortfolioPerformancePanelProps {
  history: PortfolioHistoryPoint[]
  inceptionCagr?: string | null
  inceptionXirr?: string | null
  maxDrawdown?: string | null
  rangeSummary?: Partial<Record<PerformanceRange, PerformanceSummaryMetrics>>
}

interface StoredBenchmark extends BenchmarkSearchResult {
  visible: boolean
}

function loadStoredBenchmarks(): StoredBenchmark[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.flatMap((item) => {
      if (!item || typeof item !== 'object') return []
      const row = item as Record<string, unknown>
      if (typeof row.symbol !== 'string' || typeof row.name !== 'string'
        || (row.type !== 'ETF' && row.type !== 'INDEX' && row.type !== 'EQUITY')) return []
      return [{
        symbol: row.symbol,
        name: row.name,
        exchange: typeof row.exchange === 'string' ? row.exchange : null,
        type: row.type,
        visible: row.visible !== false,
      }]
    })
  } catch {
    return []
  }
}

function lineKey(item: BenchmarkSearchResult): string {
  return `${item.type}:${item.symbol}`
}

function firstFreshDay(history: PortfolioHistoryPoint[]): string | null {
  return history.find((point) => point.dataStatus === 'FRESH' && point.marketValue !== null)?.date.slice(0, 10) ?? null
}

function PerformanceChart({ lines }: { lines: ReturnType<typeof rebasePerformanceLines>['lines'] }) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ref.current || !lines.length) return
    const dates = [...new Set(lines.flatMap((line) => line.points.map((point) => point.date)))].sort()
    if (!dates.length) return
    const chart = echarts.init(ref.current)
    const styles = getComputedStyle(document.documentElement)
    const text = styles.getPropertyValue('--text-muted').trim() || '#7c8798'
    const grid = styles.getPropertyValue('--line-subtle').trim() || '#27303d'
    const lookup = new Map(lines.map((line) => [line.key, new Map(line.points.map((point) => [point.date, point.value]))]))

    chart.setOption({
      animationDuration: 350,
      grid: { left: 8, right: 12, top: 48, bottom: 8, containLabel: true },
      legend: {
        top: 2,
        left: 'center',
        itemWidth: 18,
        itemHeight: 4,
        itemGap: 18,
        icon: 'roundRect',
        selectedMode: false,
        textStyle: { color: text, fontSize: 10 },
        formatter: (name: string) => name.split(' · ')[0],
      },
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#151c27',
        borderColor: grid,
        textStyle: { color: '#e8eef6', fontSize: 12 },
        formatter: (raw: unknown) => {
          const params = Array.isArray(raw) ? raw as Array<{ axisValueLabel?: string; seriesName?: string; value?: unknown; marker?: string }> : []
          if (!params.length) return ''
          const rows = [`<strong>${params[0].axisValueLabel ?? ''}</strong>`]
          for (const item of params) {
            const value = Array.isArray(item.value) ? item.value.at(-1) : item.value
            const numeric = typeof value === 'number' ? value : Number(value)
            if (!Number.isFinite(numeric)) continue
            rows.push(`${item.marker ?? ''} ${item.seriesName ?? ''}: ${formatSignedPercent(String(numeric / 100))}`)
          }
          return rows.join('<br/>')
        },
      },
      xAxis: {
        type: 'category',
        data: dates,
        boundaryGap: false,
        axisLine: { lineStyle: { color: grid } },
        axisLabel: { color: text, fontSize: 10, hideOverlap: true },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        splitNumber: 4,
        axisLabel: { color: text, fontSize: 10, formatter: (value: number) => `${value > 0 ? '+' : ''}${value.toFixed(1)}%` },
        splitLine: { lineStyle: { color: grid, type: 'dashed' } },
        axisLine: { show: false },
      },
      series: lines.map((line) => {
        const portfolio = line.key === 'PORTFOLIO'
        return {
          name: line.label,
          type: 'line',
          smooth: 0.16,
          showSymbol: false,
          connectNulls: false,
          z: portfolio ? 4 : 2,
          data: dates.map((date) => lookup.get(line.key)?.get(date) ?? null),
          lineStyle: { width: portfolio ? 2.6 : 1.8 },
          emphasis: { focus: 'series' },
          markLine: portfolio ? {
            silent: true,
            symbol: 'none',
            lineStyle: { color: grid, width: 1, type: 'solid' },
            label: { show: false },
            data: [{ yAxis: 0 }],
          } : undefined,
        }
      }),
    })

    const resize = () => chart.resize()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize)
    observer?.observe(ref.current)
    window.addEventListener('resize', resize)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', resize)
      chart.dispose()
    }
  }, [lines])

  return <div ref={ref} className="performance-chart" role="img" aria-label="Portfolio performance benchmark chart" />
}

export function PortfolioPerformancePanel({ history, inceptionCagr, inceptionXirr, maxDrawdown, rangeSummary }: PortfolioPerformancePanelProps) {
  const { i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const [range, setRange] = useState<PerformanceRange>('1Y')
  const [portfolioVisible, setPortfolioVisible] = useState(true)
  const [benchmarks, setBenchmarks] = useState<StoredBenchmark[]>(loadStoredBenchmarks)
  const [searchText, setSearchText] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const normalizedSearch = searchText.trim()
  const endDay = latestFreshPortfolioHistoryPoint(history)?.date.slice(0, 10) ?? null
  const startDay = range === 'ALL'
    ? firstFreshDay(history)
    : endDay ? portfolioRangeStartDay(endDay, range) : null
  const selectedServerSummary = rangeSummary?.[range]
  const fallbackRangeTwr = useMemo(() => {
    if (selectedServerSummary) return null
    if (range !== 'ALL' && !startDay) return null
    return timeWeightedReturn(history, range === 'ALL' ? undefined : startDay ?? undefined)
  }, [history, range, selectedServerSummary, startDay])
  const rangeTwr = selectedServerSummary ? selectedServerSummary.twr ?? null : fallbackRangeTwr
  const displayedCagr = selectedServerSummary ? selectedServerSummary.cagr ?? null : inceptionCagr ?? null
  const displayedXirr = selectedServerSummary ? selectedServerSummary.xirr ?? null : inceptionXirr ?? null
  const displayedMaxDrawdown = selectedServerSummary
    ? selectedServerSummary.maxDrawdown ?? null
    : range === 'ALL' ? maxDrawdown ?? null : null

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(benchmarks))
  }, [benchmarks])

  useEffect(() => {
    if (!normalizedSearch) {
      setDebouncedSearch('')
      return
    }
    const timer = window.setTimeout(() => setDebouncedSearch(normalizedSearch), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [normalizedSearch])

  const search = useQuery({
    queryKey: ['benchmark-search', debouncedSearch],
    queryFn: () => benchmarksApi.search(debouncedSearch),
    enabled: debouncedSearch.length >= 1,
    staleTime: 5 * 60_000,
    retry: false,
  })
  const historyQueries = useQueries({
    queries: benchmarks.map((item) => ({
      queryKey: ['benchmark-history', item.type, item.symbol, endDay ?? 'NO_PORTFOLIO_CLOSE'],
      queryFn: () => benchmarksApi.history(item),
      staleTime: BENCHMARK_FRESH_REFETCH_MS,
      refetchInterval: (query: { state: { data?: Awaited<ReturnType<typeof benchmarksApi.history>> } }) => {
        if (!item.visible) return false
        return query.state.data?.data?.dataStatus === 'STALE'
          ? BENCHMARK_STALE_REFETCH_MS
          : BENCHMARK_FRESH_REFETCH_MS
      },
      retry: 1,
    })),
  })

  const visibleLines = useMemo(() => {
    if (!startDay || !endDay) return []
    const lines: PerformanceLine[] = []
    if (portfolioVisible) {
      lines.push({
        key: 'PORTFOLIO',
        label: isZh ? '投资组合 TWR' : 'Portfolio TWR',
        points: portfolioTwrLevels(history, startDay, endDay),
      })
    }
    benchmarks.forEach((item, index) => {
      if (!item.visible) return
      const benchmark = historyQueries[index]?.data?.data
      if (!benchmark?.points.length) return
      lines.push({
        key: lineKey(item),
        label: `${item.symbol} · ${item.name}`,
        points: benchmarkPriceLevels(benchmark.points, startDay, endDay),
      })
    })
    return rebasePerformanceLines(lines).lines
  }, [benchmarks, endDay, history, historyQueries, isZh, portfolioVisible, startDay])

  const addBenchmark = (item: BenchmarkSearchResult) => {
    setBenchmarks((current) => current.some((row) => lineKey(row) === lineKey(item))
      ? current.map((row) => lineKey(row) === lineKey(item) ? { ...row, visible: true } : row)
      : [...current, { ...item, visible: true }])
    setSearchText('')
  }
  const toggleBenchmark = (key: string) => setBenchmarks((current) => current.map((item) => lineKey(item) === key ? { ...item, visible: !item.visible } : item))
  const removeBenchmark = (key: string) => setBenchmarks((current) => current.filter((item) => lineKey(item) !== key))
  const selectedKeys = new Set(benchmarks.map(lineKey))
  const searchResults = (search.data?.data ?? []).filter((item) => !selectedKeys.has(lineKey(item)))
  const anyLoading = historyQueries.some((query, index) => benchmarks[index]?.visible && query.isLoading)
  const anyError = historyQueries.some((query, index) => benchmarks[index]?.visible && query.isError)
  const anyStale = historyQueries.some((query, index) => benchmarks[index]?.visible && query.data?.data?.dataStatus === 'STALE')

  const title = isZh ? '投资表现（TWR）' : 'Investment performance (TWR)'
  const detail = isZh ? '剔除外部资金流影响；可叠加任意 ETF / 指数 / 股票 Benchmark' : 'Cash-flow-neutral performance with any ETF / index / equity benchmark'
  const addLabel = isZh ? '搜索 ETF、指数或股票作为基准' : 'Search any ETF, index, or equity benchmark'
  const selectedRangeLabel = isZh ? `${range} 区间` : `${range} range`
  const inceptionAnnualizedLabel = isZh ? '成立以来年化' : 'Since inception annualized'
  const inceptionMoneyWeightedLabel = isZh ? '成立以来资金加权' : 'Since inception money-weighted'
  const rangeMetricLabel = isZh ? '随当前区间联动' : 'Follows selected range'

  return <Panel className="performance-panel" title={title} detail={detail} action={<div className="chart-range-control" aria-label="Performance range">{PERFORMANCE_RANGE_OPTIONS.map((item) => <button key={item} type="button" className={range === item ? 'active' : ''} aria-pressed={range === item} onClick={() => setRange(item)}>{item}</button>)}</div>}>
    <div className="performance-controls">
      <div className="performance-series-chips">
        <button type="button" className={`performance-chip ${portfolioVisible ? 'active' : ''}`} aria-pressed={portfolioVisible} onClick={() => setPortfolioVisible((visible) => !visible)}>
          {portfolioVisible ? <Eye size={13} /> : <EyeOff size={13} />}<strong>{isZh ? '投资组合 TWR' : 'Portfolio TWR'}</strong>
        </button>
        {benchmarks.map((item) => <span className={`performance-chip benchmark-chip ${item.visible ? 'active' : ''}`} key={lineKey(item)}>
          <button type="button" className="performance-chip-toggle" aria-pressed={item.visible} onClick={() => toggleBenchmark(lineKey(item))} title={item.visible ? (isZh ? '隐藏曲线' : 'Hide line') : (isZh ? '显示曲线' : 'Show line')}>
            {item.visible ? <Eye size={13} /> : <EyeOff size={13} />}<strong>{item.symbol}</strong><em>{item.type}</em>
          </button>
          <button type="button" className="performance-chip-remove" onClick={() => removeBenchmark(lineKey(item))} aria-label={`${isZh ? '移除' : 'Remove'} ${item.symbol}`}><X size={12} /></button>
        </span>)}
      </div>
      <div className="benchmark-search-wrap">
        <Search size={14} />
        <input value={searchText} onChange={(event) => setSearchText(event.target.value)} placeholder={addLabel} aria-label={addLabel} />
        {searchText ? <button type="button" className="benchmark-search-clear" onClick={() => setSearchText('')} aria-label={isZh ? '清空搜索' : 'Clear search'}><X size={13} /></button> : null}
        {debouncedSearch && (search.isFetching || searchResults.length || search.isError || search.isSuccess) ? <div className="benchmark-search-results">
          {search.isFetching ? <small>{isZh ? '搜索中…' : 'Searching…'}</small> : null}
          {!search.isFetching && searchResults.map((item) => <button type="button" key={lineKey(item)} onClick={() => addBenchmark(item)}>
            <span><strong>{item.symbol}</strong><small>{item.name}{item.exchange ? ` · ${item.exchange}` : ''}</small></span><em>{item.type}</em><Plus size={14} />
          </button>)}
          {!search.isFetching && search.isError ? <small>{isZh ? '基准搜索暂时不可用' : 'Benchmark search is temporarily unavailable'}</small> : null}
          {!search.isFetching && !search.isError && !searchResults.length ? <small>{isZh ? '没有找到可添加的 ETF / 指数 / 股票' : 'No ETF / index / equity results to add'}</small> : null}
        </div> : null}
      </div>
    </div>
    {anyError ? <p className="performance-note performance-warning">{isZh ? '部分基准行情暂时不可用；其余曲线仍可正常比较。' : 'Some benchmark history is unavailable; remaining lines are still comparable.'}</p> : null}
    {anyStale && !anyError ? <p className="performance-note performance-warning">{isZh ? '部分基准尚未取得其所属市场最近已完成交易日的收盘数据，正在自动刷新。' : 'Some benchmarks have not published the latest completed close for their own market yet; refreshing automatically.'}</p> : null}
    {anyLoading ? <p className="performance-note">{isZh ? '正在载入基准历史…' : 'Loading benchmark history…'}</p> : null}
    {visibleLines.length ? <PerformanceChart lines={visibleLines} /> : <EmptyState title={isZh ? '请选择至少一条可用曲线' : 'Select at least one available series'} detail={isZh ? '投资组合和每个 Benchmark 都可以独立开关。' : 'Portfolio and every benchmark can be toggled independently.'} />}
    <div className="performance-summary" aria-label={isZh ? '表现摘要' : 'Performance summary'}>
      <span><small>{range} TWR</small><strong>{formatSignedPercent(rangeTwr)}</strong><em>{rangeMetricLabel}</em></span>
      <span><small>CAGR</small><strong>{formatSignedPercent(displayedCagr)}</strong><em>{selectedServerSummary ? selectedRangeLabel : inceptionAnnualizedLabel}</em></span>
      <span><small>XIRR</small><strong>{formatSignedPercent(displayedXirr)}</strong><em>{selectedServerSummary ? selectedRangeLabel : inceptionMoneyWeightedLabel}</em></span>
      <span><small>{isZh ? '最大回撤' : 'Max drawdown'}</small><strong>{formatSignedPercent(displayedMaxDrawdown)}</strong><em>{selectedServerSummary ? selectedRangeLabel : range === 'ALL' && displayedMaxDrawdown !== null ? (isZh ? '成立以来' : 'Since inception') : rangeMetricLabel}</em></span>
    </div>
    <small className="performance-footnote">{isZh
      ? '图例颜色与曲线一一对应。所有可见曲线在所选区间的第一个共同常规收盘点归零；Portfolio 使用 TWR 剔除净新增资金。摘要会明确标注区间指标与成立以来年化/资金加权指标。'
      : 'Legend colors match each curve. Visible lines are rebased to 0% at their first common regular close; Portfolio uses TWR to remove net external capital. The summary explicitly distinguishes selected-range metrics from since-inception annualized or money-weighted metrics.'}</small>
  </Panel>
}