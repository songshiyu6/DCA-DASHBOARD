import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Eye, EyeOff, Plus, Search, X } from 'lucide-react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useTranslation } from 'react-i18next'
import { benchmarksApi } from '../../lib/api/benchmarks'
import type { BenchmarkSearchResult } from '../../lib/benchmarkTypes'
import { CHART_RANGE_OPTIONS, latestFreshPortfolioHistoryPoint, portfolioRangeStartDay, type ChartRange } from '../../lib/dashboardPerformance'
import { formatSignedPercent } from '../../lib/format'
import { benchmarkPriceLevels, portfolioTwrLevels, rebasePerformanceLines, type PerformanceLine } from '../../lib/performanceSeries'
import type { PortfolioHistoryPoint } from '../../types'
import { EmptyState } from '../DataState'
import { Panel } from '../Panel'
import './portfolio-performance.css'

echarts.use([LineChart, GridComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

const STORAGE_KEY = 'dca-performance-benchmarks-v1'

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
      if (typeof row.symbol !== 'string' || typeof row.name !== 'string' || (row.type !== 'ETF' && row.type !== 'INDEX')) return []
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
      grid: { left: 8, right: 12, top: 20, bottom: 8, containLabel: true },
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
      series: lines.map((line, index) => ({
        name: line.label,
        type: 'line',
        smooth: 0.16,
        showSymbol: false,
        connectNulls: false,
        z: index === 0 ? 4 : 2,
        data: dates.map((date) => lookup.get(line.key)?.get(date) ?? null),
        lineStyle: { width: index === 0 ? 2.6 : 1.8 },
        emphasis: { focus: 'series' },
        markLine: index === 0 ? {
          silent: true,
          symbol: 'none',
          lineStyle: { color: grid, width: 1, type: 'solid' },
          label: { show: false },
          data: [{ yAxis: 0 }],
        } : undefined,
      })),
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

export function PortfolioPerformancePanel({ history }: { history: PortfolioHistoryPoint[] }) {
  const { i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const [range, setRange] = useState<ChartRange>('1Y')
  const [portfolioVisible, setPortfolioVisible] = useState(true)
  const [benchmarks, setBenchmarks] = useState<StoredBenchmark[]>(loadStoredBenchmarks)
  const [searchText, setSearchText] = useState('')
  const deferredSearch = useDeferredValue(searchText.trim())

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(benchmarks))
  }, [benchmarks])

  const search = useQuery({
    queryKey: ['benchmark-search', deferredSearch],
    queryFn: () => benchmarksApi.search(deferredSearch),
    enabled: deferredSearch.length >= 1,
    staleTime: 5 * 60_000,
  })
  const historyQueries = useQueries({
    queries: benchmarks.map((item) => ({
      queryKey: ['benchmark-history', item.type, item.symbol],
      queryFn: () => benchmarksApi.history(item),
      staleTime: 6 * 60 * 60_000,
      retry: 1,
    })),
  })

  const endDay = latestFreshPortfolioHistoryPoint(history)?.date.slice(0, 10) ?? null
  const startDay = endDay ? portfolioRangeStartDay(endDay, range) : null
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

  const title = isZh ? '投资表现' : 'Investment performance'
  const detail = isZh ? '剔除新增资金影响的 TWR，可叠加任意 ETF / 指数' : 'Cash-flow-neutral TWR with any ETF / index benchmarks'
  const addLabel = isZh ? '搜索 ETF 或指数作为基准' : 'Search any ETF or index benchmark'

  return <Panel className="performance-panel" title={title} detail={detail} action={<div className="chart-range-control" aria-label="Performance range">{CHART_RANGE_OPTIONS.map((item) => <button key={item} type="button" className={range === item ? 'active' : ''} aria-pressed={range === item} onClick={() => setRange(item)}>{item}</button>)}</div>}>
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
        {deferredSearch && (search.isFetching || searchResults.length || search.isError) ? <div className="benchmark-search-results">
          {search.isFetching ? <small>{isZh ? '搜索中…' : 'Searching…'}</small> : null}
          {!search.isFetching && searchResults.map((item) => <button type="button" key={lineKey(item)} onClick={() => addBenchmark(item)}>
            <span><strong>{item.symbol}</strong><small>{item.name}{item.exchange ? ` · ${item.exchange}` : ''}</small></span><em>{item.type}</em><Plus size={14} />
          </button>)}
          {!search.isFetching && search.isError ? <small>{isZh ? '基准搜索暂时不可用' : 'Benchmark search is temporarily unavailable'}</small> : null}
          {!search.isFetching && !search.isError && !searchResults.length ? <small>{isZh ? '没有找到可添加的 ETF / 指数' : 'No ETF / index results to add'}</small> : null}
        </div> : null}
      </div>
    </div>
    {anyError ? <p className="performance-note performance-warning">{isZh ? '部分基准行情暂时不可用；其余曲线仍可正常比较。' : 'Some benchmark history is unavailable; remaining lines are still comparable.'}</p> : null}
    {anyLoading ? <p className="performance-note">{isZh ? '正在载入基准历史…' : 'Loading benchmark history…'}</p> : null}
    {visibleLines.length ? <PerformanceChart lines={visibleLines} /> : <EmptyState title={isZh ? '请选择至少一条可用曲线' : 'Select at least one available series'} detail={isZh ? '投资组合和每个 Benchmark 都可以独立开关。' : 'Portfolio and every benchmark can be toggled independently.'} />}
    <small className="performance-footnote">{isZh
      ? '所有可见曲线在所选区间的第一个共同常规收盘点归零。Portfolio 使用 TWR 剔除净新增资金；ETF Benchmark 优先使用复权收盘价，指数使用 Yahoo 可用的对应收盘/复权值。'
      : 'All visible lines are rebased to 0% at their first common regular close. Portfolio uses TWR to remove net external capital; ETF benchmarks prefer adjusted close while indices use the corresponding Yahoo close/adjusted value.'}</small>
  </Panel>
}
