import { describe, expect, it } from 'vitest'
import { formatPortfolioTooltip, toPortfolioChartPoints } from './PortfolioChart'

describe('PortfolioChart data adapter', () => {
  it('keeps missing market value as a gap while retaining net investment', () => {
    expect(toPortfolioChartPoints([
      { date: '2026-08-26', marketValue: null, netInvested: '100.00', dataStatus: 'PARTIAL' },
      { date: '2026-08-27', marketValue: '101.00', netInvested: '100.00', dataStatus: 'FRESH' },
    ])).toEqual([
      { date: '2026-08-26', marketValue: null, netInvested: 100 },
      { date: '2026-08-27', marketValue: 101, netInvested: 100 },
    ])
  })

  it('shows both NLV and net investment while the interactive series remains NLV-only', () => {
    const points = toPortfolioChartPoints([
      { date: '2026-08-20', marketValue: '58454.54', netInvested: '59120.00', dataStatus: 'FRESH' },
    ])
    const timestamp = Date.parse('2026-08-20T12:00:00Z')

    const tooltip = formatPortfolioTooltip([
      {
        axisValue: timestamp,
        seriesName: '净清算价值',
        value: [timestamp, 58454.54],
        marker: '<span>nlv</span>',
        dataIndex: 0,
      },
    ], points, '净清算价值', '净投入', '#00aa66')

    expect(tooltip).toContain('2026-08-20')
    expect(tooltip).toContain('净清算价值: $58,454.54')
    expect(tooltip).toContain('净投入: $59,120.00')
    expect(tooltip).toContain('#00aa66')
  })

  it('extracts the money value from an ECharts time-series tuple', () => {
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
