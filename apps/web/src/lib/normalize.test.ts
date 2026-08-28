import { describe, expect, it } from 'vitest'
import {
  normalizeDashboardData,
  normalizeInstrument,
  normalizeMetrics,
  normalizePlan,
  normalizePricePoints,
  normalizeQuote,
  normalizeRecommendation,
  normalizeSession,
  normalizeSettings,
  normalizeTransactions,
} from './api/normalize'

describe('domain response normalizers', () => {
  it('normalizes the auth domain without changing the session contract', () => {
    expect(normalizeSession({ authenticated: true, username: 'alice' })).toEqual({ authenticated: true, username: 'alice' })
  })

  it('normalizes the instrument domain and preserves nullable profile values', () => {
    expect(normalizeInstrument({ id: 'voo', symbol: 'VOO', name: 'Vanguard S&P 500 ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Vanguard', expenseRatio: '0.0003', aum: null, dividendYield: null, nav: null, tracked: true })).toMatchObject({ symbol: 'VOO', expenseRatio: '0.0003', aum: null, tracked: true })
  })

  it('normalizes the market quote and keeps NAV separate from market price', () => {
    expect(normalizeQuote({ symbol: 'VOO', price: 620.21, previousClose: '617.62', change: '2.59', changePercent: '0.004193', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'YAHOO', nav: null })).toMatchObject({ symbol: 'VOO', price: '620.21', nav: null })
  })

  it('normalizes portfolio fields while retaining partial market values as null', () => {
    expect(normalizeDashboardData({ summary: { marketValue: '101.00' }, portfolioHistory: [{ date: '2026-08-26', marketValue: null, netInvested: '100.00', status: 'PARTIAL' }] }).portfolioHistory).toEqual([{ date: '2026-08-26', marketValue: null, netInvested: '100.00', dataStatus: 'PARTIAL' }])
  })

  it('normalizes price and metric domains without filling missing adjusted prices', () => {
    expect(normalizePricePoints([{ date: '2026-08-27', close: 620.21, adjustedClose: null }])).toEqual([{ date: '2026-08-27', open: undefined, close: '620.21', adjustedClose: null, volume: undefined }])
    expect(normalizeMetrics({ oneMonth: null, fiftyTwoWeekHigh: '630.00', dataStatus: 'PARTIAL' })).toMatchObject({ oneMonth: null, fiftyTwoWeekHigh: '630.00', dataStatus: 'PARTIAL' })
  })

  it('normalizes plan and recommendation domains without changing decimal strings', () => {
    expect(normalizePlan({ id: 'plan-1', name: 'Core', currency: 'USD', frequency: 'MONTHLY', monthlyBudget: '1500.00', startDate: '2026-01-01', executionStartDay: 1, executionEndDay: 7, status: 'ACTIVE', assets: [{ symbol: 'VOO', targetWeight: '0.50000000' }] })).toMatchObject({ id: 'plan-1', monthlyBudget: '1500.00', assets: [{ targetWeight: '0.50000000' }] })
    expect(normalizeRecommendation({ amount: '1000.00', method: 'CONTRIBUTION_FIRST', dataStatus: 'STALE', items: [] })).toMatchObject({ amount: '1000.00', dataStatus: 'STALE' })
  })

  it('normalizes transaction and settings domains with stable defaults', () => {
    expect(normalizeTransactions([{ id: 'txn-1', instrumentSymbol: 'VOO', transactionType: 'BUY', tradeDate: '2026-08-27', quantity: '1.2', unitPrice: '620.00', amount: null, fee: '0.00', currency: 'USD' }])).toMatchObject([{ id: 'txn-1', quantity: '1.2', amount: null }])
    expect(normalizeSettings({ primaryProvider: 'YAHOO', fallbackProvider: 'NONE', theme: 'DARK', timezone: 'Asia/Tokyo' })).toMatchObject({ primaryProvider: 'YAHOO', fallbackProvider: 'NONE', theme: 'DARK', timezone: 'Asia/Tokyo' })
  })
})
