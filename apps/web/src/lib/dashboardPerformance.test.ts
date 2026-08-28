import { describe, expect, it } from 'vitest'
import { filterPortfolioHistory, latestDayPerformance, timeWeightedReturn, withCurrentPortfolioPoint, ytdPerformance, ytdTimeWeightedReturn } from './dashboardPerformance'
import type { PortfolioHistoryPoint } from '../types'

const point = (date: string, marketValue: string, netInvested: string): PortfolioHistoryPoint => ({ date, marketValue, netInvested, dataStatus: 'FRESH' })

describe('dashboard performance', () => {
  it('removes same-day external contributions from daily profit', () => {
    const result = latestDayPerformance([
      point('2026-08-27', '1000', '1000'),
      point('2026-08-28T16:00:00Z', '1200', '1100'),
    ])

    expect(Number(result.pnl)).toBeCloseTo(100, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.1, 8)
  })

  it('calculates time-weighted return without treating later contributions as performance', () => {
    const history = [
      point('2026-01-02', '1000', '1000'),
      point('2026-01-03', '1100', '1000'),
      point('2026-01-04', '1650', '1500'),
    ]

    expect(Number(timeWeightedReturn(history))).toBeCloseTo(0.15, 8)
    expect(Number(ytdTimeWeightedReturn(history))).toBeCloseTo(0.15, 8)
  })

  it('shows YTD profit as money without treating contributions as profit', () => {
    const result = ytdPerformance([
      point('2025-12-31', '1000', '1000'),
      point('2026-08-28T16:00:00Z', '1300', '1200'),
    ])

    expect(Number(result.pnl)).toBeCloseTo(100, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.1, 8)
  })

  it('uses net capital as the YTD profit baseline when the portfolio starts this year', () => {
    const result = ytdPerformance([
      point('2026-08-07', '1000', '1000'),
      point('2026-08-28T16:00:00Z', '1015', '1000'),
    ])

    expect(Number(result.pnl)).toBeCloseTo(15, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.015, 8)
  })

  it('replaces the stored daily point with a transient current valuation', () => {
    const history = [point('2026-08-27', '1000', '1000'), point('2026-08-28', '1010', '1000')]
    const current = withCurrentPortfolioPoint(history, '1025', '1000', '2026-08-28T16:12:00Z', 'FRESH')

    expect(current).toHaveLength(2)
    expect(current[1]).toMatchObject({ date: '2026-08-28T16:12:00Z', marketValue: '1025', netInvested: '1000' })
  })

  it('keeps a live valuation on the backend portfolio business date across UTC midnight', () => {
    const history = [point('2026-08-27', '1000', '1000'), point('2026-08-28', '1010', '1000')]
    const current = withCurrentPortfolioPoint(history, '1025', '1000', '2026-08-29T00:15:00Z', 'FRESH')

    expect(current.at(-1)?.date).toBe('2026-08-28T00:15:00Z')
  })

  it('filters chart history using the selected range', () => {
    const history = [
      point('2025-12-31', '900', '900'),
      point('2026-01-02', '1000', '1000'),
      point('2026-08-28T16:00:00Z', '1100', '1000'),
    ]

    expect(filterPortfolioHistory(history, 'YTD').map((item) => item.date)).toEqual([
      '2026-01-02',
      '2026-08-28T16:00:00Z',
    ])
  })
})
