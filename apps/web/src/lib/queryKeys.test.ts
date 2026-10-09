import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { invalidateAutoDcaQueries, invalidateFundQueries, invalidatePlanQueries, invalidateTransactionQueries, queryKeys } from './queryKeys'

describe('query key and invalidation policy', () => {
  it('invalidates only the current plan projections after a plan mutation', async () => {
    const queryClient = new QueryClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    await invalidatePlanQueries(queryClient, 'plan-1')

    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      queryKeys.plans,
      queryKeys.dashboard,
      queryKeys.planCycles('plan-1'),
      queryKeys.recommendation('plan-1'),
      queryKeys.contributionAnalysis('plan-1'),
    ])
  })

  it('invalidates plan amount and QQQM recommendations after a CNY fund change', async () => {
    const queryClient = new QueryClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    await invalidateFundQueries(queryClient, 'fund-1')
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      queryKeys.funds, queryKeys.autoDcaRules, queryKeys.multiCurrencyReports,
      queryKeys.dashboard, queryKeys.planCycleFamily, queryKeys.recommendationFamily,
      queryKeys.fundPurchases('fund-1'), queryKeys.fundNav('fund-1'), queryKeys.fundCalendar('fund-1'),
    ])
  })

  it('invalidates all plan cycle and recommendation queries after auto DCA rule changes', async () => {
    const queryClient = new QueryClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    await invalidateAutoDcaQueries(queryClient)
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      queryKeys.autoDcaRules, queryKeys.multiCurrencyReports,
      queryKeys.dashboard, queryKeys.planCycleFamily, queryKeys.recommendationFamily,
    ])
  })

  it('invalidates linked plan and every multi-currency reporting range after a transaction mutation', async () => {
    const queryClient = new QueryClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    await invalidateTransactionQueries(queryClient, 'plan-1')

    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      queryKeys.transactions,
      queryKeys.dashboard,
      queryKeys.portfolioPerformance,
      queryKeys.multiCurrencyReports,
      queryKeys.planCycles('plan-1'),
      queryKeys.recommendation('plan-1'),
      queryKeys.contributionAnalysis('plan-1'),
    ])
  })
})
