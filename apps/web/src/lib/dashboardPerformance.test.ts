import { describe, expect, it } from 'vitest'
import { annualizedTimeWeightedReturn, CHART_RANGE_OPTIONS, filterPortfolioHistory, latestDayPerformance, latestFreshPortfolioHistoryPoint, livePerformanceSinceLastClose, livePerformanceSincePreviousTradingClose, marketBusinessDay, portfolioRangeStartDay, timeWeightedReturn, withCurrentPortfolioPoint, ytdPerformance, ytdTimeWeightedReturn } from './dashboardPerformance'
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

  it('measures live session profit from the last fresh regular close and ignores a stale PARTIAL tail', () => {
    const history: PortfolioHistoryPoint[] = [
      point('2026-08-27', '1000', '1000'),
      point('2026-08-28', '1010', '1000'),
      { date: '2026-08-31', marketValue: '1010', netInvested: '1000', dataStatus: 'PARTIAL' },
      { date: '2026-09-01', marketValue: '1010', netInvested: '1000', dataStatus: 'PARTIAL' },
    ]
    const result = livePerformanceSinceLastClose(history, '1040', '1010')

    expect(Number(result.pnl)).toBeCloseTo(20, 8)
    expect(Number(result.returnRate)).toBeCloseTo(20 / 1010, 8)
    expect(latestFreshPortfolioHistoryPoint(history)?.date).toBe('2026-08-28')
  })

  it('uses America/New_York midnight only as the daily rollover boundary', () => {
    expect(marketBusinessDay('2026-09-03T03:59:59Z')).toBe('2026-09-02')
    expect(marketBusinessDay('2026-09-03T04:00:00Z')).toBe('2026-09-03')
    expect(marketBusinessDay('2026-01-15T04:59:59Z')).toBe('2026-01-14')
    expect(marketBusinessDay('2026-01-15T05:00:00Z')).toBe('2026-01-15')
  })

  it('keeps Today anchored to the previous trading close after the current-day close is stored', () => {
    const history = [
      point('2026-09-04', '1000', '1000'),
      point('2026-09-08', '1100', '1000'),
    ]
    const result = livePerformanceSincePreviousTradingClose(history, '2026-09-08', '1110', '1000')

    expect(Number(result.pnl)).toBeCloseTo(110, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.11, 8)
  })

  it('rolls the Today baseline to the prior close on the next New York day', () => {
    const history = [
      point('2026-09-04', '1000', '1000'),
      point('2026-09-08', '1100', '1000'),
    ]
    const result = livePerformanceSincePreviousTradingClose(history, '2026-09-09', '1110', '1000')

    expect(Number(result.pnl)).toBeCloseTo(10, 8)
    expect(Number(result.returnRate)).toBeCloseTo(10 / 1100, 8)
  })

  it('removes current-day external flows from previous-close Today performance', () => {
    const history = [point('2026-09-04', '1000', '1000')]
    const result = livePerformanceSincePreviousTradingClose(history, '2026-09-08', '1320', '1200')

    expect(Number(result.pnl)).toBeCloseTo(120, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.12, 8)
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

  it('does not let PARTIAL carry-forward values change time-weighted performance', () => {
    const history: PortfolioHistoryPoint[] = [
      point('2026-08-27', '1000', '1000'),
      point('2026-08-28', '1100', '1000'),
      { date: '2026-08-31', marketValue: '1100', netInvested: '1200', dataStatus: 'PARTIAL' },
    ]

    expect(Number(timeWeightedReturn(history))).toBeCloseTo(0.1, 8)
    expect(Number(ytdTimeWeightedReturn(history))).toBeCloseTo(0.1, 8)
  })

  it('annualizes time-weighted performance without treating contributions as return', () => {
    const history = [
      point('2025-01-01', '1000', '1000'),
      point('2026-01-01', '1200', '1100'),
    ]
    const expected = Math.pow(1.1, 365.2425 / 365) - 1

    expect(Number(annualizedTimeWeightedReturn(history))).toBeCloseTo(expected, 8)
  })

  it('requires more than one valuation day to annualize performance', () => {
    expect(annualizedTimeWeightedReturn([point('2026-08-28', '1000', '1000')])).toBeNull()
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

  it('orders the range controls with YTD at the far right', () => {
    expect(CHART_RANGE_OPTIONS).toEqual(['1M', '3M', '1Y', 'YTD'])
  })

  it('calculates exact calendar windows and clamps short months', () => {
    expect(portfolioRangeStartDay('2026-08-28', '1M')).toBe('2026-07-28')
    expect(portfolioRangeStartDay('2026-08-28', '3M')).toBe('2026-05-28')
    expect(portfolioRangeStartDay('2026-08-28', '1Y')).toBe('2025-08-28')
    expect(portfolioRangeStartDay('2026-08-28', 'YTD')).toBe('2026-01-01')
    expect(portfolioRangeStartDay('2026-03-31', '1M')).toBe('2026-02-28')
  })

  it('returns distinct history slices for each selected range', () => {
    const history = [
      point('2025-07-01', '800', '800'),
      point('2025-09-01', '900', '900'),
      point('2026-02-01', '1000', '1000'),
      point('2026-06-01', '1050', '1000'),
      point('2026-08-28T16:00:00Z', '1100', '1000'),
    ]

    expect(filterPortfolioHistory(history, '1M').map((item) => item.date)).toEqual([
      '2026-08-28T16:00:00Z',
    ])
    expect(filterPortfolioHistory(history, '3M').map((item) => item.date)).toEqual([
      '2026-06-01',
      '2026-08-28T16:00:00Z',
    ])
    expect(filterPortfolioHistory(history, '1Y').map((item) => item.date)).toEqual([
      '2025-09-01',
      '2026-02-01',
      '2026-06-01',
      '2026-08-28T16:00:00Z',
    ])
    expect(filterPortfolioHistory(history, 'YTD').map((item) => item.date)).toEqual([
      '2026-02-01',
      '2026-06-01',
      '2026-08-28T16:00:00Z',
    ])
  })

  it('ends chart ranges at the last fresh close instead of a PARTIAL replay tail', () => {
    const history: PortfolioHistoryPoint[] = [
      point('2026-07-28', '1000', '1000'),
      point('2026-08-28', '1100', '1000'),
      { date: '2026-08-31', marketValue: '1100', netInvested: '1000', dataStatus: 'PARTIAL' },
      { date: '2026-09-02', marketValue: '1100', netInvested: '1000', dataStatus: 'PARTIAL' },
    ]

    expect(filterPortfolioHistory(history, '1M').map((item) => item.date)).toEqual(['2026-07-28', '2026-08-28'])
  })
})
