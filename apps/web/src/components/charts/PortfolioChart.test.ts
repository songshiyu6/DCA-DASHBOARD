import { describe, expect, it } from 'vitest'
import { formatPortfolioTooltip, resolvePortfolioChartEnd, resolvePortfolioChartStart, toPortfolioChartPoints } from './PortfolioChart'

describe('PortfolioChart data adapter', () => {
  it('keeps a missing trading-day valuation as a gap while retaining net investment', () => {
    expect(toPortfolioChartPoints([
      { date: '2026-08-26', marketValue: null, netInvested: '100.00', dataStatus: 'PARTIAL' },
      { date: '2026-08-27', marketValue: '101.00', netInvested: '100.00', dataStatus: 'FRESH' },
    ])).toEqual([
      { date: '2026-08-26', marketValue: null, netInvested: 100 },
      { date: '2026-08-27', marketValue: 101, netInvested: 100 },
    ])
  })

  it('removes weekend dates so Friday and Monday are adjacent chart sessions', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-28', marketValue: '58909.71', netInvested: '59000', dataStatus: 'FRESH' },
      { date: '2026-08-29', marketValue: '58909.71', netInvested: '59000', dataStatus: 'PARTIAL' },
      { date: '2026-08-30', marketValue: '58909.71', netInvested: '59000', dataStatus: 'PARTIAL' },
      { date: '2026-08-31', marketValue: '59020.00', netInvested: '59000', dataStatus: 'FRESH' },
    ])

    expect(points.map((point) => point.date)).toEqual(['2026-08-28', '2026-08-31'])
    expect(points.map((point) => point.marketValue)).toEqual([58909.71, 59020])
  })

  it('does not connect across a missing trading session', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-09-01', marketValue: '59020.00', netInvested: '59000', dataStatus: 'FRESH' },
      { date: '2026-09-02', marketValue: '59020.00', netInvested: '59000', dataStatus: 'PARTIAL' },
      { date: '2026-09-03', marketValue: '59100.00', netInvested: '59000', dataStatus: 'FRESH' },
    ])

    expect(points.map((point) => point.date)).toEqual(['2026-09-01', '2026-09-02', '2026-09-03'])
    expect(points.map((point) => point.marketValue)).toEqual([59020, null, 59100])
  })

  it('does not render a carried-forward PARTIAL tail as regular-close valuation', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-28', marketValue: '58909.71', netInvested: '59000', dataStatus: 'FRESH' },
      { date: '2026-08-31', marketValue: '58909.71', netInvested: '59000', dataStatus: 'PARTIAL' },
      { date: '2026-09-01', marketValue: '58909.71', netInvested: '59000', dataStatus: 'PARTIAL' },
    ])

    expect(points.map((point) => point.marketValue)).toEqual([58909.71, null, null])
    expect(resolvePortfolioChartEnd(points, '2026-09-01')).toBe('2026-08-28')
  })

  it('clamps every requested window to the first funded portfolio point', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-03', marketValue: '0', netInvested: '0', dataStatus: 'FRESH' },
      { date: '2026-08-07', marketValue: '59000', netInvested: '59000', dataStatus: 'FRESH' },
      { date: '2026-08-28', marketValue: '58900', netInvested: '59000', dataStatus: 'FRESH' },
    ])

    expect(resolvePortfolioChartStart(points, '2026-01-01')).toBe('2026-08-07')
    expect(resolvePortfolioChartStart(points, '2026-05-28')).toBe('2026-08-07')
    expect(resolvePortfolioChartStart(points, '2026-07-28')).toBe('2026-08-07')
    expect(resolvePortfolioChartStart(points, '2026-08-20')).toBe('2026-08-20')
  })

  it('falls back to the first available session when funded history is unavailable', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-05', marketValue: '0', netInvested: '0', dataStatus: 'FRESH' },
      { date: '2026-08-06', marketValue: '0', netInvested: '0', dataStatus: 'FRESH' },
    ])

    expect(resolvePortfolioChartStart(points, '2026-01-01')).toBe('2026-08-05')
  })

  it('shows both NLV and net investment from a category-axis session', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-20', marketValue: '58454.54', netInvested: '59120.00', dataStatus: 'FRESH' },
    ])

    const tooltip = formatPortfolioTooltip([
      {
        axisValue: '2026-08-20',
        seriesName: '净清算价值',
        value: 58454.54,
        marker: '<span>nlv</span>',
        dataIndex: 0,
      },
    ], points, '净清算价值', '净投入', '#00aa66')

    expect(tooltip).toContain('2026-08-20')
    expect(tooltip).toContain('净清算价值: $58,454.54')
    expect(tooltip).toContain('净投入: $59,120.00')
    expect(tooltip).toContain('#00aa66')
  })

  it('still formats an ECharts numeric time tuple when supplied by legacy callers', () => {
    const timestamp = Date.parse('2026-08-20T12:00:00Z')
    const tooltip = formatPortfolioTooltip([
      {
        axisValue: timestamp,
        axisValueLabel: '2026-08-20',
        seriesName: 'Net Liq Value',
        value: [timestamp, 58454.54],
      },
    ], [], 'Net Liq Value', 'Net investment')

    expect(tooltip).toContain('2026-08-20')
    expect(tooltip).toContain('Net Liq Value: $58,454.54')
  })
})
