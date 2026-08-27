export type DataStatus = 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE' | 'INSUFFICIENT_HISTORY'

export interface DataMeta {
  status: DataStatus
  source?: string
  asOf?: string
  retrievedAt?: string
  message?: string
}

export interface ApiResult<T> {
  data: T
  meta: DataMeta
}

export interface Instrument {
  id: string
  symbol: string
  name: string
  exchange: string
  currency: string
  instrumentType: 'ETF'
  issuer: string
  expenseRatio: string | null
  aum: string | null
  dividendYield: string | null
  nav: string | null
  tracked: boolean
  dataStatus?: DataStatus
}

export interface Quote {
  symbol: string
  price: string
  previousClose: string
  change: string
  changePercent: string
  bid?: string | null
  ask?: string | null
  marketTimestamp: string
  retrievedAt: string
  source: string
  status?: DataStatus
  nav?: string | null
  navDate?: string | null
}

export interface PricePoint {
  date: string
  open?: string
  high?: string
  low?: string
  close: string
  adjustedClose: string | null
  volume?: string
}

export interface EtfMetrics {
  oneDay: string | null
  oneMonth: string | null
  threeMonths: string | null
  ytd: string | null
  oneYear: string | null
  threeYearCagr: string | null
  fiftyTwoWeekHigh: string | null
  fiftyTwoWeekLow: string | null
  currentDrawdown: string | null
  maxDrawdown1Y: string | null
  dataStatus?: DataStatus
  asOf?: string
}

export interface Holding {
  symbol: string
  name: string
  shares: string
  avgCost: string
  price: string | null
  todayPercent: string | null
  marketValue: string | null
  costBasis: string
  unrealizedPnl: string | null
  returnPercent: string | null
  allocation: string | null
  dataStatus?: DataStatus | null
}

export interface AllocationRow {
  symbol: string
  targetWeight: string | null
  actualWeight: string | null
  drift: string | null
  marketValue: string | null
}

export type CycleStatus = 'UPCOMING' | 'OPEN' | 'PARTIAL' | 'COMPLETED' | 'SKIPPED'

export interface PlanAsset {
  id?: string
  symbol: string
  targetWeight: string
}

export interface PlanCycleAsset {
  symbol: string
  targetWeight: string
  plannedAmount: string
  executedAmount: string
}

export interface PlanCycle {
  id: string
  period: string
  plannedAmount: string
  executedAmount: string
  status: CycleStatus
  openedAt?: string | null
  completedAt?: string | null
  assets: PlanCycleAsset[]
}

export interface InvestmentPlan {
  id: string
  name: string
  currency: string
  frequency: 'MONTHLY' | 'WEEKLY' | 'BIWEEKLY'
  monthlyBudget: string
  startDate: string
  executionStartDay: number
  executionEndDay: number
  status: 'ACTIVE' | 'PAUSED' | 'ARCHIVED'
  assets: PlanAsset[]
  cycles?: PlanCycle[]
}

export interface RecommendationItem {
  symbol: string
  currentWeight: string
  targetWeight: string
  gap: string
  suggestedAmount: string
  currentValue?: string | null
  positiveGap?: string | null
  valueGap?: string | null
}

export interface Recommendation {
  amount: string
  items: RecommendationItem[]
  method: string
  dataStatus: DataStatus
  message?: string
}

export type TransactionType = 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE'

export interface Transaction {
  id: string
  instrumentSymbol: string
  planCycleId?: string | null
  transactionType: TransactionType
  tradeDate: string
  quantity: string | null
  unitPrice: string | null
  amount: string | null
  fee: string
  currency: 'USD'
  total?: string | null
  notes?: string | null
  ledgerOrder?: number | null
}

export interface DashboardSummary {
  marketValue: string
  costBasis: string
  netInvested: string
  unrealizedPnl: string | null
  realizedPnl: string
  dividendIncome: string
  totalPnl: string | null
  xirr: string | null
}

export interface PortfolioHistoryPoint {
  date: string
  marketValue: string
  netInvested: string
  dataStatus?: DataStatus
}

export interface ContributionProgress {
  year: number
  executed: string
  planned: string
  remaining: string
  executionRate: string
  months: Array<{
    period: string
    planned: string
    executed: string
    status: CycleStatus | 'NONE'
  }>
}

export interface NextDca {
  period: string
  amount: string
  daysRemaining: number
  items: RecommendationItem[]
  dataStatus?: DataStatus
  message?: string
}

export interface DashboardData {
  summary: DashboardSummary
  nextDca: NextDca | null
  portfolioHistory: PortfolioHistoryPoint[]
  holdings: Holding[]
  allocation: AllocationRow[]
  contributionProgress: ContributionProgress | null
}

export interface AppSettings {
  baseCurrency: 'USD'
  primaryProvider: 'YAHOO' | 'TWELVE_DATA' | 'ALPHA_VANTAGE'
  fallbackProvider: 'YAHOO' | 'TWELVE_DATA' | 'ALPHA_VANTAGE' | 'NONE'
  twelveDataConfigured: boolean
  alphaVantageConfigured: boolean
  theme: 'SYSTEM' | 'LIGHT' | 'DARK'
  timezone: string
}

export interface Session {
  authenticated: boolean
  username?: string
}

export interface TransactionInput {
  instrumentSymbol: string
  planCycleId?: string | null
  transactionType: TransactionType
  tradeDate: string
  quantity?: string
  unitPrice?: string
  amount?: string
  fee: string
  currency: 'USD'
  notes?: string
}

export interface TransactionCsvRow {
  date: string
  type: TransactionType
  symbol: string
  quantity?: string | null
  price?: string | null
  fee?: string | null
  amount?: string | null
  planCycleId?: string | null
  notes?: string | null
}

export interface TransactionImportPreview {
  batchId: string
  rows: TransactionInput[]
  errors: string[]
  duplicateRows?: number[]
  sourceRows?: TransactionCsvRow[]
}

export interface TransactionImportCommit {
  batchId: string
  importedRows: number
  transactionIds: string[]
}
