import Decimal from 'decimal.js-light'

Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_UP })

export function decimalMax(left: Decimal, right: Decimal | string | number): Decimal {
  return left.gte(right) ? left : new Decimal(right)
}

export function decimalMin(left: Decimal, right: Decimal | string | number): Decimal {
  return left.lte(right) ? left : new Decimal(right)
}

export function decimalFloor(value: Decimal, places = 2): Decimal {
  return value.toDecimalPlaces(places, Decimal.ROUND_DOWN)
}

export function decimal(value: string | number | null | undefined): Decimal {
  if (value === null || value === undefined || value === '') return new Decimal(0)
  try {
    return new Decimal(value)
  } catch {
    return new Decimal(0)
  }
}

function groupInteger(value: string): string {
  return value.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function signedParts(value: string, fractionDigits: number): { sign: string; integer: string; fraction: string } {
  const rounded = decimal(value).toFixed(fractionDigits)
  const negative = rounded.startsWith('-')
  const unsigned = negative ? rounded.slice(1) : rounded
  const [integer, fraction = ''] = unsigned.split('.')
  return { sign: negative ? '-' : '', integer: groupInteger(integer), fraction }
}

export function formatMoney(value: string | number | null | undefined, currency = 'USD', fractionDigits = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const symbols: Record<string, string> = { USD: '$', EUR: '€', JPY: '¥' }
  const parts = signedParts(String(value), fractionDigits)
  const symbol = symbols[currency] ?? currency + ' '
  return `${parts.sign}${symbol}${parts.integer}${fractionDigits ? `.${parts.fraction}` : ''}`
}

export function formatSignedMoney(value: string | number | null | undefined, currency = 'USD', fractionDigits = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const amount = decimal(value)
  if (amount.isZero()) return formatMoney('0', currency, fractionDigits)
  return `${amount.gt(0) ? '+' : ''}${formatMoney(amount.toString(), currency, fractionDigits)}`
}

export function formatNumber(value: string | number | null | undefined, fractionDigits = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const parts = signedParts(String(value ?? 0), fractionDigits)
  return `${parts.sign}${parts.integer}${fractionDigits ? `.${parts.fraction}` : ''}`
}

export function formatShares(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const amount = decimal(value)
  const rendered = amount.toFixed(8).replace(/\.?(0+)$/, '')
  return rendered === '-0' ? '0' : rendered
}

export function formatPercent(value: string | number | null | undefined, fractionDigits = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const parts = signedParts(decimal(value).mul(100).toString(), fractionDigits)
  return `${parts.sign}${parts.integer}${fractionDigits ? `.${parts.fraction}` : ''}%`
}

export function formatSignedPercent(value: string | number | null | undefined, fractionDigits = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const amount = decimal(value)
  if (amount.isZero()) return formatPercent('0', fractionDigits)
  return `${amount.gt(0) ? '+' : ''}${formatPercent(amount.toString(), fractionDigits)}`
}

export function formatCompactMoney(value: string | number | null | undefined, currency = 'USD'): string {
  if (value === null || value === undefined || value === '') return '—'
  const amount = decimal(value)
  const absolute = amount.abs()
  const units: Array<[Decimal, string]> = [
    [new Decimal('1000000000'), 'B'],
    [new Decimal('1000000'), 'M'],
    [new Decimal('1000'), 'K'],
  ]
  const unit = units.find(([threshold]) => absolute.gte(threshold))
  if (!unit) return formatMoney(amount.toString(), currency)
  const compact = amount.div(unit[0]).toFixed(1)
  return `${amount.lt(0) ? '-' : ''}${currency === 'USD' ? '$' : currency + ' '}${compact.replace(/\.0$/, '')}${unit[1]}`
}

export function formatDate(value: string | null | undefined, options?: Intl.DateTimeFormatOptions): string {
  if (!value) return '—'
  const date = new Date(value.includes('T') ? value : `${value}T12:00:00Z`)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, options ?? { month: 'short', day: 'numeric', year: 'numeric' }).format(date)
}

export function formatTime(value: string | null | undefined, timeZone = 'America/New_York'): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', timeZone }).format(date)
}

export function formatPeriod(period: string): string {
  const [year, month] = period.split('-').map(Number)
  if (!year || !month) return period
  return new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(new Date(year, month - 1, 1))
}

export function formatRelativeDate(value: string | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const diffDays = Math.round((Date.now() - date.getTime()) / 86_400_000)
  if (diffDays <= 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  return `${diffDays}d ago`
}
