import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { invalidatePlanQueries, invalidateTransactionQueries, queryKeys } from './queryKeys'

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

  it('invalidates linked plan projections without broad prefixes after a transaction mutation', async () => {
    const queryClient = new QueryClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    await invalidateTransactionQueries(queryClient, 'plan-1')

    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      queryKeys.transactions,
      queryKeys.dashboard,
      queryKeys.planCycles('plan-1'),
      queryKeys.recommendation('plan-1'),
      queryKeys.contributionAnalysis('plan-1'),
    ])
  })
})
