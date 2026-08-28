import Decimal from 'decimal.js-light'
import type {
  AllocationRow,
  AppSettings,
  ApiResult,
  DashboardData,
  DataMeta,
  EtfMetrics,
  Instrument,
  InvestmentPlan,
  NextDca,
  PlanCycle,
  PlanCycleAsset,
  PricePoint,
  Quote,
  Recommendation,
  Session,
  Transaction,
  TransactionInput,
} from '../types'
import { decimal, decimalFloor, decimalMax, decimalMin } from './format'

export { parseTransactionCsv } from './transactionCsv'

// Deterministic demo adapter and test data; never use it as live API resilience.
const TODAY = '2026-08-27'
const STORAGE_KEY = 'dca-terminal-fixture-state'

export const fixtureInstruments: Instrument[] = [
  { id: 'voo', symbol: 'VOO', name: 'Vanguard S&P 500 ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Vanguard', expenseRatio: '0.0003', aum: '712400000000', dividendYield: '0.0115', nav: '620.10', tracked: true },
  { id: 'qqq', symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', currency: 'USD', instrumentType: 'ETF', issuer: 'Invesco', expenseRatio: '0.0020', aum: '344800000000', dividendYield: '0.0061', nav: '574.21', tracked: true },
  { id: 'schd', symbol: 'SCHD', name: 'Schwab U.S. Dividend Equity ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Charles Schwab', expenseRatio: '0.0006', aum: '78200000000', dividendYield: '0.0374', nav: '27.49', tracked: true },
  { id: 'vti', symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Vanguard', expenseRatio: '0.0003', aum: '579200000000', dividendYield: '0.0116', nav: '327.49', tracked: true },
  { id: 'vt', symbol: 'VT', name: 'Vanguard Total World Stock ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Vanguard', expenseRatio: '0.0006', aum: '185600000000', dividendYield: '0.0201', nav: '124.08', tracked: false },
  { id: 'sgov', symbol: 'SGOV', name: 'iShares 0-3 Month Treasury Bond ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'BlackRock', expenseRatio: '0.0009', aum: '29600000000', dividendYield: '0.0432', nav: '100.51', tracked: false },
  { id: 'avuv', symbol: 'AVUV', name: 'Avantis U.S. Small Cap Value ETF', exchange: 'NYSE Arca', currency: 'USD', instrumentType: 'ETF', issuer: 'Avantis Investors', expenseRatio: '0.0025', aum: '11800000000', dividendYield: '0.0138', nav: '104.02', tracked: false },
]

const quoteBySymbol: Record<string, Quote> = {
  VOO: { symbol: 'VOO', price: '620.21', previousClose: '617.62', change: '2.59', changePercent: '0.004193', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  QQQ: { symbol: 'QQQ', price: '574.35', previousClose: '571.96', change: '2.39', changePercent: '0.004178', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  SCHD: { symbol: 'SCHD', price: '27.52', previousClose: '27.38', change: '0.14', changePercent: '0.005113', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  VTI: { symbol: 'VTI', price: '327.61', previousClose: '326.18', change: '1.43', changePercent: '0.004383', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  VT: { symbol: 'VT', price: '124.17', previousClose: '123.68', change: '0.49', changePercent: '0.003962', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  SGOV: { symbol: 'SGOV', price: '100.53', previousClose: '100.51', change: '0.02', changePercent: '0.000199', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
  AVUV: { symbol: 'AVUV', price: '104.13', previousClose: '103.48', change: '0.65', changePercent: '0.006281', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'FIXTURE', status: 'STALE' },
}

const profileBySymbol: Record<string, Instrument> = Object.fromEntries(fixtureInstruments.map((instrument) => [instrument.symbol, instrument]))

function dateKey(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function generatePriceHistory(basePrice: string, drift: string, seed: number): PricePoint[] {
  const start = new Date('2021-08-27T12:00:00Z')
  const end = new Date(`${TODAY}T12:00:00Z`)
  const dates: Date[] = []
  for (const current = new Date(start); current <= end; current.setUTCDate(current.getUTCDate() + 1)) {
    const day = current.getUTCDay()
    if (day !== 0 && day !== 6) dates.push(new Date(current))
  }

  return dates.map((date, index) => {
    const progress = new Decimal(index).div(Math.max(dates.length - 1, 1))
    const wave = new Decimal(Math.sin(index / 29 + seed) * 0.018)
    const trend = new Decimal(1).plus(new Decimal(drift).mul(progress)).plus(wave)
    const close = decimalMax(new Decimal(basePrice).mul(trend), new Decimal('1')).toDecimalPlaces(6)
    const dailyRange = new Decimal('0.006').plus(new Decimal(Math.abs(Math.sin(index / 11 + seed)) * 0.004))
    const high = close.mul(new Decimal(1).plus(dailyRange)).toDecimalPlaces(6)
    const low = close.mul(new Decimal(1).minus(dailyRange)).toDecimalPlaces(6)
    return {
      date: dateKey(date),
      open: close.mul('0.998').toDecimalPlaces(6).toFixed(6),
      high: high.toFixed(6),
      low: low.toFixed(6),
      close: close.toFixed(6),
      adjustedClose: close.mul(new Decimal('0.9985')).toDecimalPlaces(6).toFixed(6),
      volume: String(8_000_000 + ((index * 71_131 + seed * 9_173) % 11_000_000)),
    }
  })
}

const historyBySymbol: Record<string, PricePoint[]> = {
  VOO: generatePriceHistory('438.12', '0.42', 1),
  QQQ: generatePriceHistory('368.45', '0.55', 3),
  SCHD: generatePriceHistory('28.12', '-0.02', 5),
  VTI: generatePriceHistory('221.77', '0.39', 7),
  VT: generatePriceHistory('89.34', '0.31', 9),
  SGOV: generatePriceHistory('100.13', '0.004', 11),
  AVUV: generatePriceHistory('77.32', '0.28', 13),
}

const allocation: AllocationRow[] = [
  { symbol: 'VOO', targetWeight: '0.50', actualWeight: '0.541', drift: '0.041', marketValue: '15376.20' },
  { symbol: 'QQQ', targetWeight: '0.30', actualWeight: '0.284', drift: '-0.016', marketValue: '8072.04' },
  { symbol: 'SCHD', targetWeight: '0.20', actualWeight: '0.175', drift: '-0.025', marketValue: '4973.38' },
]

const holdings = [
  { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', shares: '24.7884', avgCost: '511.42', price: '620.21', todayPercent: '0.004193', marketValue: '15376.20', costBasis: '12678.01', unrealizedPnl: '2698.19', returnPercent: '0.2128', allocation: '0.541' },
  { symbol: 'QQQ', name: 'Invesco QQQ Trust', shares: '14.0612', avgCost: '482.11', price: '574.35', todayPercent: '0.004178', marketValue: '8072.04', costBasis: '6777.24', unrealizedPnl: '1294.80', returnPercent: '0.1910', allocation: '0.284' },
  { symbol: 'SCHD', name: 'Schwab U.S. Dividend Equity ETF', shares: '180.6175', avgCost: '23.46', price: '27.52', todayPercent: '0.005113', marketValue: '4973.38', costBasis: '4238.22', unrealizedPnl: '735.16', returnPercent: '0.1735', allocation: '0.175' },
]

const months = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12']

function makeCycle(period: string, index: number, status: PlanCycle['status'], executedAmount: string): PlanCycle {
  const plannedAmount = '1500.00'
  const assets: PlanCycleAsset[] = [
    { symbol: 'VOO', targetWeight: '0.50', plannedAmount: '750.00', executedAmount: index < 2 ? '750.00' : index === 7 ? '750.00' : '0.00' },
    { symbol: 'QQQ', targetWeight: '0.30', plannedAmount: '450.00', executedAmount: index < 2 ? '450.00' : index === 7 ? '350.00' : '0.00' },
    { symbol: 'SCHD', targetWeight: '0.20', plannedAmount: '300.00', executedAmount: index < 2 ? '300.00' : index === 7 ? '0.00' : '0.00' },
  ]
  return { id: `cycle-${period}`, period, plannedAmount, executedAmount, status, assets, openedAt: status === 'UPCOMING' ? null : `${period}-01T14:00:00Z`, completedAt: status === 'COMPLETED' ? `${period}-07T20:00:00Z` : null }
}

const cycles: PlanCycle[] = months.map((month, index) => {
  const period = `2026-${month}`
  if (index < 2) return makeCycle(period, index, 'COMPLETED', '1500.00')
  if (index < 7) return makeCycle(period, index, 'COMPLETED', '1500.00')
  if (index === 7) return makeCycle(period, index, 'PARTIAL', '1100.00')
  if (index === 8) return makeCycle(period, index, 'OPEN', '0.00')
  return makeCycle(period, index, 'UPCOMING', '0.00')
})

export const fixturePlan: InvestmentPlan = {
  id: 'core-plan',
  name: 'Core ETF Plan',
  currency: 'USD',
  frequency: 'MONTHLY',
  monthlyBudget: '1500.00',
  startDate: '2026-01-01',
  executionStartDay: 1,
  executionEndDay: 7,
  status: 'ACTIVE',
  assets: [
    { id: 'plan-voo', symbol: 'VOO', targetWeight: '0.50' },
    { id: 'plan-qqq', symbol: 'QQQ', targetWeight: '0.30' },
    { id: 'plan-schd', symbol: 'SCHD', targetWeight: '0.20' },
  ],
  cycles,
}

export const fixtureTransactions: Transaction[] = [
  { id: 'txn-001', instrumentSymbol: 'VOO', transactionType: 'BUY', tradeDate: '2026-01-05', quantity: '1.4150', unitPrice: '530.04', amount: null, fee: '0.00', currency: 'USD', total: '750.00', planCycleId: 'cycle-2026-01', notes: 'Monthly DCA' },
  { id: 'txn-002', instrumentSymbol: 'QQQ', transactionType: 'BUY', tradeDate: '2026-01-05', quantity: '0.8450', unitPrice: '532.54', amount: null, fee: '0.00', currency: 'USD', total: '450.00', planCycleId: 'cycle-2026-01', notes: 'Monthly DCA' },
  { id: 'txn-003', instrumentSymbol: 'SCHD', transactionType: 'BUY', tradeDate: '2026-01-05', quantity: '10.6640', unitPrice: '28.15', amount: null, fee: '0.00', currency: 'USD', total: '300.00', planCycleId: 'cycle-2026-01', notes: 'Monthly DCA' },
  { id: 'txn-004', instrumentSymbol: 'VOO', transactionType: 'BUY', tradeDate: '2026-08-03', quantity: '1.2134', unitPrice: '617.60', amount: null, fee: '0.00', currency: 'USD', total: '749.99', planCycleId: 'cycle-2026-08', notes: 'Monthly DCA' },
  { id: 'txn-005', instrumentSymbol: 'QQQ', transactionType: 'BUY', tradeDate: '2026-08-03', quantity: '0.6119', unitPrice: '571.99', amount: null, fee: '0.00', currency: 'USD', total: '350.00', planCycleId: 'cycle-2026-08', notes: 'Monthly DCA' },
  { id: 'txn-006', instrumentSymbol: 'SCHD', transactionType: 'DIVIDEND', tradeDate: '2026-07-07', quantity: null, unitPrice: null, amount: '42.18', fee: '0.00', currency: 'USD', total: '42.18', planCycleId: null, notes: 'Cash dividend' },
]

const fixtureHistory: DashboardData['portfolioHistory'] = [
  ['2026-01-02', '18420.00', '18000.00'], ['2026-01-30', '19980.40', '19500.00'], ['2026-02-27', '20715.28', '21000.00'], ['2026-03-31', '21894.12', '22500.00'], ['2026-04-30', '22455.88', '24000.00'], ['2026-05-29', '24117.35', '25500.00'], ['2026-06-30', '25789.40', '27000.00'], ['2026-07-31', '27084.21', '28500.00'], ['2026-08-27', '28421.62', '29600.00'],
].map(([date, marketValue, netInvested]) => ({ date, marketValue, netInvested }))

export const fixtureDashboard: DashboardData = {
  summary: { marketValue: '28421.62', costBasis: '25180.39', netInvested: '29600.00', unrealizedPnl: '3241.23', realizedPnl: '0.00', dividendIncome: '42.18', totalPnl: '3283.41', xirr: '0.1421' },
  nextDca: null,
  portfolioHistory: fixtureHistory,
  holdings,
  allocation,
  contributionProgress: { year: 2026, executed: '8750.00', planned: '12000.00', remaining: '3250.00', executionRate: '0.7292', months: [
    { period: '2026-01', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-02', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-03', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-04', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-05', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-06', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-07', planned: '1500.00', executed: '1500.00', status: 'COMPLETED' }, { period: '2026-08', planned: '1500.00', executed: '1100.00', status: 'PARTIAL' }, { period: '2026-09', planned: '1500.00', executed: '0.00', status: 'OPEN' }, { period: '2026-10', planned: '1500.00', executed: '0.00', status: 'UPCOMING' }, { period: '2026-11', planned: '1500.00', executed: '0.00', status: 'UPCOMING' }, { period: '2026-12', planned: '1500.00', executed: '0.00', status: 'UPCOMING' },
  ] },
}

export const fixtureSettings: AppSettings = { baseCurrency: 'USD', primaryProvider: 'YAHOO', fallbackProvider: 'TWELVE_DATA', twelveDataConfigured: false, alphaVantageConfigured: false, theme: 'SYSTEM', timezone: 'America/New_York' }

const fixtureMeta: DataMeta = { status: 'STALE', source: 'FIXTURE', asOf: TODAY, retrievedAt: '2026-08-27T20:02:00Z', message: 'Demo data only. This workspace is not connected to the API.' }

interface FixtureState {
  instruments: Instrument[]
  transactions: Transaction[]
  plans: InvestmentPlan[]
  settings: AppSettings
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

const initialState: FixtureState = {
  instruments: clone(fixtureInstruments),
  transactions: clone(fixtureTransactions),
  plans: [clone(fixturePlan)],
  settings: clone(fixtureSettings),
}

function loadState(): FixtureState {
  if (typeof localStorage === 'undefined') return clone(initialState)
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (!saved) return clone(initialState)
    const parsed = JSON.parse(saved) as Partial<FixtureState>
    if (!Array.isArray(parsed.instruments) || !Array.isArray(parsed.transactions) || !Array.isArray(parsed.plans)) return clone(initialState)
    return { ...clone(initialState), ...parsed, settings: parsed.settings ?? clone(initialState.settings) } as FixtureState
  } catch {
    return clone(initialState)
  }
}

const state = loadState()

function persist(): void {
  if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

function localResult<T>(data: T, message = fixtureMeta.message): ApiResult<T> {
  return { data, meta: { ...fixtureMeta, message } }
}

function trackedSymbols(): string[] {
  return state.instruments.filter((instrument) => instrument.tracked).map((instrument) => instrument.symbol)
}

export function getFixtureInstruments(): ApiResult<Instrument[]> {
  return localResult(state.instruments.filter((instrument) => instrument.tracked).map(clone))
}

export function searchFixtureInstruments(query: string): ApiResult<Instrument[]> {
  const normalized = query.trim().toLowerCase()
  return localResult(state.instruments.filter((instrument) => !normalized || instrument.symbol.toLowerCase().includes(normalized) || instrument.name.toLowerCase().includes(normalized)).map(clone))
}

export function getFixtureInstrument(symbol: string): ApiResult<Instrument> {
  const instrument = state.instruments.find((item) => item.symbol === symbol.toUpperCase()) ?? profileBySymbol[symbol.toUpperCase()]
  if (!instrument) throw new Error('Instrument not found')
  return localResult(clone(instrument))
}

export function getFixtureQuote(symbol: string): ApiResult<Quote> {
  const quote = quoteBySymbol[symbol.toUpperCase()]
  if (!quote) throw new Error('Quote not found')
  return localResult(clone(quote))
}

export function getFixturePrices(symbol: string, range = '1Y'): ApiResult<PricePoint[]> {
  const all = historyBySymbol[symbol.toUpperCase()] ?? []
  const quote = quoteBySymbol[symbol.toUpperCase()]
  if (range === '1D' && quote) return localResult(generateIntradayHistory(quote))
  if (all.length === 0) return localResult([])
  if (range === '5Y' || range === 'ALL') return localResult(clone(all))
  const latestDate = all.at(-1)?.date.slice(0, 10) ?? TODAY
  const start = rangeStart(latestDate, range)
  return localResult(clone(all.filter((point) => point.date.slice(0, 10) >= start)))
}

export function getFixtureMetrics(symbol: string): ApiResult<EtfMetrics> {
  const quote = quoteBySymbol[symbol.toUpperCase()]
  const history = historyBySymbol[symbol.toUpperCase()] ?? []
  if (!quote || history.length === 0) throw new Error('Metrics not found')
  const latestPoint = history.at(-1)
  const latestDate = latestPoint?.date.slice(0, 10) ?? TODAY
  const latest = decimal(latestPoint?.adjustedClose ?? latestPoint?.close)
  const oneMonthPoint = pointAtOrBefore(history, subtractCalendarMonths(latestDate, 1))
  const threeMonthPoint = pointAtOrBefore(history, subtractCalendarMonths(latestDate, 3))
  const ytdPoint = pointAtOrBefore(history, `${Number(latestDate.slice(0, 4)) - 1}-12-31`)
  const oneYearPoint = pointAtOrBefore(history, subtractCalendarMonths(latestDate, 12))
  const threeYearPoint = pointAtOrBefore(history, subtractCalendarMonths(latestDate, 36))
  const oneYearHistory = history.filter((point) => point.date.slice(0, 10) >= subtractCalendarDays(latestDate, 365))
  const adjusted = history.map((point) => decimal(point.adjustedClose ?? point.close))
  const runningPeak = adjusted.reduce((peak, point) => decimalMax(peak, point), new Decimal(0))
  let peak = new Decimal(0)
  let maxDrawdown = new Decimal(0)
  for (const point of oneYearHistory) {
    const adjustedPoint = decimal(point.adjustedClose ?? point.close)
    peak = decimalMax(peak, adjustedPoint)
    maxDrawdown = decimalMin(maxDrawdown, adjustedPoint.div(peak).minus(1))
  }
  const high52Week = oneYearHistory.reduce((high, point) => decimalMax(high, point.high ?? point.close), new Decimal(0))
  const low52Week = oneYearHistory.reduce((low, point) => decimalMin(low, point.low ?? point.close), new Decimal('999999999'))
  const relativeReturn = (start: PricePoint | undefined): string | null => start ? latest.div(start.adjustedClose ?? start.close).minus(1).toFixed(6) : null
  const cagr = threeYearPoint ? latest.div(threeYearPoint.adjustedClose ?? threeYearPoint.close).pow(new Decimal('365.2425').div(daysBetween(threeYearPoint.date, latestDate))).minus(1).toFixed(6) : null
  return localResult({
    oneDay: quote.changePercent,
    oneMonth: relativeReturn(oneMonthPoint),
    threeMonths: relativeReturn(threeMonthPoint),
    ytd: relativeReturn(ytdPoint),
    oneYear: relativeReturn(oneYearPoint),
    threeYearCagr: cagr,
    fiftyTwoWeekHigh: oneYearHistory.length ? high52Week.toFixed(2) : null,
    fiftyTwoWeekLow: oneYearHistory.length ? low52Week.toFixed(2) : null,
    currentDrawdown: runningPeak.gt(0) ? latest.div(runningPeak).minus(1).toFixed(6) : null,
    maxDrawdown1Y: oneYearHistory.length ? maxDrawdown.toFixed(6) : null,
  })
}

function subtractCalendarMonths(value: string, months: number): string {
  const date = new Date(`${value}T12:00:00Z`)
  const targetMonth = date.getUTCMonth() - months
  const targetYear = date.getUTCFullYear() + Math.floor(targetMonth / 12)
  const normalizedMonth = ((targetMonth % 12) + 12) % 12
  const lastDay = new Date(Date.UTC(targetYear, normalizedMonth + 1, 0)).getUTCDate()
  const targetDay = Math.min(date.getUTCDate(), lastDay)
  return dateKey(new Date(Date.UTC(targetYear, normalizedMonth, targetDay, 12)))
}

function subtractCalendarDays(value: string, days: number): string {
  const date = new Date(`${value}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() - days)
  return dateKey(date)
}

function rangeStart(latestDate: string, range: string): string {
  if (range === '1W') {
    const date = new Date(`${latestDate}T12:00:00Z`)
    date.setUTCDate(date.getUTCDate() - 7)
    return dateKey(date)
  }
  if (range === '1M') return subtractCalendarMonths(latestDate, 1)
  if (range === '3M') return subtractCalendarMonths(latestDate, 3)
  if (range === 'YTD') return `${latestDate.slice(0, 4)}-01-01`
  if (range === '1Y') return subtractCalendarMonths(latestDate, 12)
  if (range === '3Y') return subtractCalendarMonths(latestDate, 36)
  return subtractCalendarMonths(latestDate, 12)
}

function pointAtOrBefore(history: PricePoint[], targetDate: string): PricePoint | undefined {
  for (let index = history.length - 1; index >= 0; index -= 1) {
    if (history[index].date.slice(0, 10) <= targetDate) return history[index]
  }
  return undefined
}

function daysBetween(start: string, end: string): number {
  const startTime = new Date(`${start.slice(0, 10)}T12:00:00Z`).getTime()
  const endTime = new Date(`${end.slice(0, 10)}T12:00:00Z`).getTime()
  return Math.max((endTime - startTime) / 86_400_000, 1)
}

function generateIntradayHistory(quote: Quote): PricePoint[] {
  const open = new Decimal(quote.previousClose)
  const close = new Decimal(quote.price)
  const start = new Date(`${TODAY}T13:30:00Z`)
  return Array.from({ length: 79 }, (_, index) => {
    const progress = new Decimal(index).div(78)
    const wave = new Decimal(Math.sin(index / 3.5) * 0.0015)
    const price = open.plus(close.minus(open).mul(progress)).mul(new Decimal(1).plus(wave)).toDecimalPlaces(6)
    const range = new Decimal('0.0012')
    return {
      date: new Date(start.getTime() + index * 5 * 60_000).toISOString(),
      open: price.toFixed(6),
      high: price.mul(new Decimal(1).plus(range)).toFixed(6),
      low: price.mul(new Decimal(1).minus(range)).toFixed(6),
      close: price.toFixed(6),
      adjustedClose: price.toFixed(6),
      volume: String(90_000 + index * 1_250),
    }
  })
}

export function calculateRecommendation(plan: InvestmentPlan, amount = plan.monthlyBudget): Recommendation {
  const currentValue: Record<string, Decimal> = Object.fromEntries(allocation.map((row) => [row.symbol, decimal(row.marketValue)]))
  const currentWeights: Record<string, Decimal> = Object.fromEntries(allocation.map((row) => [row.symbol, decimal(row.actualWeight)]))
  const currentTotal = allocation.reduce((sum, row) => sum.plus(decimal(row.marketValue)), new Decimal(0))
  const contribution = decimal(amount).toDecimalPlaces(2)
  const afterContribution = currentTotal.plus(contribution)
  const gaps = plan.assets.map((asset) => {
    const target = decimal(asset.targetWeight)
    const current = currentWeights[asset.symbol] ?? new Decimal(0)
    const valueGap = afterContribution.mul(target).minus(currentValue[asset.symbol] ?? 0)
    return { asset, current, target, valueGap, weightGap: target.minus(current) }
  })
  const positiveGaps = gaps.filter((item) => item.valueGap.gt(0))
  const gapTotal = positiveGaps.reduce((sum, item) => sum.plus(item.valueGap), new Decimal(0))
  const distributionTotal = gapTotal.gt(0) ? gapTotal : gaps.reduce((sum, item) => sum.plus(item.target), new Decimal(0))
  const exactAmounts = gaps.map((item) => {
    const basis = gapTotal.gt(0) ? decimalMax(item.valueGap, 0) : item.target
    return { ...item, exact: contribution.mul(basis).div(distributionTotal) }
  })
  const rounded = exactAmounts.map((item) => ({ ...item, rounded: decimalFloor(item.exact, 2) }))
  let centsRemaining = contribution.mul(100).minus(rounded.reduce((sum, item) => sum.plus(item.rounded.mul(100)), new Decimal(0)))
  const remainderOrder = [...rounded].sort((a, b) => b.exact.minus(b.rounded).comparedTo(a.exact.minus(a.rounded)))
  for (const item of remainderOrder) {
    if (centsRemaining.lte(0)) break
    const target = rounded.find((candidate) => candidate.asset.symbol === item.asset.symbol)
    if (target) target.rounded = target.rounded.plus('0.01')
    centsRemaining = centsRemaining.minus(1)
  }
  return {
    amount: contribution.toFixed(2),
    method: 'CONTRIBUTION_FIRST',
    dataStatus: 'STALE',
    message: fixtureMeta.message,
    items: rounded.map((item) => ({ symbol: item.asset.symbol, currentWeight: item.current.toFixed(6), targetWeight: item.target.toFixed(6), gap: item.weightGap.toFixed(6), suggestedAmount: item.rounded.toFixed(2) })),
  }
}

export function getFixturePlan(id = 'core-plan'): ApiResult<InvestmentPlan> {
  const plan = state.plans.find((item) => item.id === id) ?? state.plans[0]
  if (!plan) throw new Error('Plan not found')
  return localResult(clone(plan))
}

export function getFixturePlans(): ApiResult<InvestmentPlan[]> {
  return localResult(state.plans.map(clone))
}

export function getFixtureRecommendation(id = 'core-plan'): ApiResult<Recommendation> {
  const plan = state.plans.find((item) => item.id === id) ?? state.plans[0]
  if (!plan) throw new Error('Plan not found')
  return localResult(calculateRecommendation(plan))
}

export function getFixtureCycles(id = 'core-plan'): ApiResult<PlanCycle[]> {
  const plan = state.plans.find((item) => item.id === id) ?? state.plans[0]
  if (!plan) throw new Error('Plan not found')
  return localResult(clone(plan.cycles ?? []))
}

export function getFixtureDashboard(): ApiResult<DashboardData> {
  const plan = state.plans.find((item) => item.status === 'ACTIVE')
  const recommendation = plan ? calculateRecommendation(plan) : null
  const nextDca: NextDca | null = recommendation ? { period: '2026-09', amount: recommendation.amount, daysRemaining: 13, items: recommendation.items } : null
  return localResult({ ...clone(fixtureDashboard), nextDca, contributionProgress: plan ? clone(fixtureDashboard.contributionProgress) : null })
}

export function getFixtureTransactions(): ApiResult<Transaction[]> {
  return localResult([...state.transactions].sort((a, b) => b.tradeDate.localeCompare(a.tradeDate)).map(clone))
}

function fixtureTransactionFromInput(input: TransactionInput, id: string): Transaction {
  const fee = decimal(input.fee)
  const quantity = input.quantity ? decimal(input.quantity) : null
  const unitPrice = input.unitPrice ? decimal(input.unitPrice) : null
  const amount = input.amount ? decimal(input.amount) : null
  let total = amount ?? new Decimal(0)
  if (quantity && unitPrice) total = quantity.mul(unitPrice).plus(input.transactionType === 'BUY' ? fee : input.transactionType === 'SELL' ? fee.neg() : 0)
  return { id, instrumentSymbol: input.instrumentSymbol.toUpperCase(), transactionType: input.transactionType, tradeDate: input.tradeDate, quantity: quantity?.toFixed(8) ?? null, unitPrice: unitPrice?.toFixed(6) ?? null, amount: amount?.toFixed(6) ?? null, fee: fee.toFixed(6), currency: input.currency, total: total.toFixed(6), planCycleId: input.planCycleId ?? null, notes: input.notes ?? null }
}

let localTransactionSequence = 0

export function createFixtureTransaction(input: TransactionInput): ApiResult<Transaction> {
  const transaction = fixtureTransactionFromInput(input, `local-${Date.now()}-${localTransactionSequence += 1}`)
  state.transactions = [transaction, ...state.transactions]
  persist()
  return localResult(clone(transaction))
}

export function updateFixtureTransaction(id: string, input: TransactionInput): ApiResult<Transaction> {
  const index = state.transactions.findIndex((transaction) => transaction.id === id)
  if (index < 0) throw new Error('Transaction not found')
  const transaction = fixtureTransactionFromInput(input, id)
  state.transactions[index] = transaction
  persist()
  return localResult(clone(transaction))
}

export function importFixtureTransactions(inputs: TransactionInput[]): ApiResult<Transaction[]> {
  const created = inputs.map((input) => createFixtureTransaction(input).data)
  return localResult(created)
}

export function deleteFixtureTransaction(id: string): ApiResult<{ id: string }> {
  state.transactions = state.transactions.filter((transaction) => transaction.id !== id)
  persist()
  return localResult({ id })
}

export function updateFixturePlan(id: string, patch: Partial<InvestmentPlan>): ApiResult<InvestmentPlan> {
  const index = state.plans.findIndex((plan) => plan.id === id)
  if (index < 0) throw new Error('Plan not found')
  state.plans[index] = { ...state.plans[index], ...clone(patch), assets: patch.assets ? clone(patch.assets) : state.plans[index].assets }
  persist()
  return localResult(clone(state.plans[index]))
}

export function createFixturePlan(input: Omit<InvestmentPlan, 'id' | 'cycles'>): ApiResult<InvestmentPlan> {
  const plan: InvestmentPlan = { ...clone(input), id: `plan-${Date.now()}`, cycles: [] }
  state.plans = [...state.plans, plan]
  persist()
  return localResult(clone(plan))
}

export function trackFixtureInstrument(symbol: string): ApiResult<Instrument> {
  const instrument = state.instruments.find((item) => item.symbol === symbol.toUpperCase())
  if (!instrument) throw new Error('Instrument not found')
  instrument.tracked = true
  persist()
  return localResult(clone(instrument))
}

export function untrackFixtureInstrument(symbol: string): ApiResult<Instrument> {
  const instrument = state.instruments.find((item) => item.symbol === symbol.toUpperCase())
  if (!instrument) throw new Error('Instrument not found')
  instrument.tracked = false
  persist()
  return localResult(clone(instrument))
}

export function getFixtureSettings(): ApiResult<AppSettings> {
  return localResult(clone(state.settings))
}

export function updateFixtureSettings(patch: Partial<AppSettings>): ApiResult<AppSettings> {
  state.settings = { ...state.settings, ...patch }
  persist()
  return localResult(clone(state.settings))
}

export function getFixtureSession(): ApiResult<Session> {
  return localResult({ authenticated: true, username: 'demo' })
}

export function getTrackedSymbols(): string[] {
  return trackedSymbols()
}
