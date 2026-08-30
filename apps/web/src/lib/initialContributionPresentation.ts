import { decimal } from './format'

export function isInitialContributionPeriod(
  period: string,
  status: string,
  planStartDate: string | null | undefined,
  initialPrincipal: string | number | null | undefined,
  dcaExecuted: string | number | null | undefined = '0',
): boolean {
  if (!planStartDate || status !== 'SKIPPED' || period !== planStartDate.slice(0, 7)) return false
  return decimal(initialPrincipal).gt(0) && decimal(dcaExecuted).isZero()
}
