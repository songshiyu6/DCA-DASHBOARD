import { describe, expect, it } from 'vitest'
import { toPortfolioChartPoints } from './PortfolioChart'

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
})
