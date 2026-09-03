import { decimal } from './format'

export interface DailySettlementLike {
  marketValue: string | null
  netInvested: string
  dataStatus?: string
}

export interface DailyPerformance {
  pnl: string | null
  returnRate: string | null
}

export function dailyPerformanceFromSettlement(
  settlement: DailySettlementLike | null | undefined,
  marketValue: string | undefined,
  netInvested: string | undefined,
): DailyPerformance {
  if (!settlement || settlement.marketValue === null || !marketValue || !netInvested) {
    return { pnl: null, returnRate: null }
  }
  if (settlement.dataStatus && settlement.dataStatus !== 'FRESH') {
    return { pnl: null, returnRate: null }
  }

  const openingValue = decimal(settlement.marketValue)
  if (openingValue.lte(0)) return { pnl: null, returnRate: null }

  const externalFlow = decimal(netInvested).minus(settlement.netInvested)
  const pnl = decimal(marketValue).minus(openingValue).minus(externalFlow)
  return { pnl: pnl.toString(), returnRate: pnl.div(openingValue).toString() }
}
