import { describe, expect, it } from 'vitest'
import type { PortfolioHistoryPoint } from '../types'
import { benchmarkPriceLevels, portfolioTwrLevels, rebasePerformanceLines } from './performanceSeries'

const point = (date: string, marketValue: string, netInvested: string): PortfolioHistoryPoint => ({
  date,
  marketValue,
  netInvested,
  dataStatus: 'FRESH',
})

describe('performance series', () => {
  it('removes additional capital from the portfolio return curve', () => {
    const levels = portfolioTwrLevels([
      point('2026-08-03', '1000', '1000'),
      point('2026-08-04', '1100', '1000'),
      point('2026-08-05', '1650', '1500'),
    ], '2026-08-01', '2026-08-31')

    expect(levels.map((item) => item.value)).toEqual([1, 1.1, 1.15])
  })

  it('breaks across an untrusted trading-day gap instead of inventing a return', () => {
    const levels = portfolioTwrLevels([
      point('2026-08-03', '1000', '1000'),
      { date: '2026-08-04', marketValue: '1000', netInvested: '1000', dataStatus: 'PARTIAL' },
      point('2026-08-05', '1200', '1000'),
      point('2026-08-06', '1260', '1000'),
    ], '2026-08-01', '2026-08-31')

    expect(levels).toEqual([
      { date: '2026-08-03', value: 1 },
      { date: '2026-08-04', value: null },
      { date: '2026-08-05', value: 1 },
      { date: '2026-08-06', value: 1.05 },
    ])
  })

  it('keeps a benchmark trading date even when the US market is closed', () => {
    const levels = benchmarkPriceLevels([
      { date: '2026-09-04', value: '4.20' },
      { date: '2026-09-07', value: '4.25' },
    ], '2026-09-01', '2026-09-10')

    expect(levels).toEqual([
      { date: '2026-09-04', value: 4.2 },
      { date: '2026-09-07', value: 4.25 },
    ])
  })

  it('rebases portfolio and multiple benchmarks to one common trading close', () => {
    const portfolio = portfolioTwrLevels([
      point('2026-08-03', '1000', '1000'),
      point('2026-08-04', '1100', '1000'),
      point('2026-08-05', '1210', '1000'),
    ], '2026-08-01', '2026-08-31')
    const sp500 = benchmarkPriceLevels([
      { date: '2026-08-04', value: '5000' },
      { date: '2026-08-05', value: '5050' },
    ], '2026-08-01', '2026-08-31')
    const ndx = benchmarkPriceLevels([
      { date: '2026-08-04', value: '20000' },
      { date: '2026-08-05', value: '20400' },
    ], '2026-08-01', '2026-08-31')

    const result = rebasePerformanceLines([
      { key: 'portfolio', label: 'Portfolio', points: portfolio },
      { key: '^GSPC', label: 'S&P 500', points: sp500 },
      { key: '^NDX', label: 'NASDAQ-100', points: ndx },
    ])

    expect(result.startDay).toBe('2026-08-04')
    expect(result.lines[0].points[0].value).toBeCloseTo(0, 10)
    expect(result.lines[0].points[1].value).toBeCloseTo(10, 10)
    expect(result.lines[1].points[1].value).toBeCloseTo(1, 10)
    expect(result.lines[2].points[1].value).toBeCloseTo(2, 10)
  })
})
