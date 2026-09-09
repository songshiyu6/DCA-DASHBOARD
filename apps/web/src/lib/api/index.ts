import { authApi } from './auth'
import { contributionsApi } from './contributions'
import { fundsApi } from './funds'
import { instrumentsApi } from './instruments'
import { plansApi } from './plans'
import { portfolioApi } from './portfolio'
import { reportingApi } from './reporting'
import { settingsApi } from './settings'
import { transactionsApi } from './transactions'

type AppMode = 'live' | 'demo'

function resolveAppMode(value: string | undefined): AppMode {
  if (!value || value === 'live') return 'live'
  if (value === 'demo') return 'demo'
  throw new Error(`Invalid VITE_APP_MODE "${value}". Expected "live" or "demo".`)
}

const APP_MODE = resolveAppMode(import.meta.env.VITE_APP_MODE)

const liveApi = {
  ...authApi,
  ...instrumentsApi,
  ...portfolioApi,
  ...plansApi,
  ...contributionsApi,
  ...transactionsApi,
  ...settingsApi,
  ...fundsApi,
  ...reportingApi,
}

type DemoApi = typeof import('../demo/api').demoApi

let demoApiPromise: Promise<DemoApi> | undefined

function loadDemoApi(): Promise<DemoApi> {
  demoApiPromise ??= import('../demo/api').then(({ demoApi }) => demoApi)
  return demoApiPromise
}

const emptyDemoResult = <T>(data: T) => Promise.resolve({ data, meta: { status: 'FRESH' as const, source: 'FIXTURE' } })
const fundDemoUnavailable = async (): Promise<never> => { throw new Error('China fund management is unavailable in demo mode') }
const reportingDemoUnavailable = async (): Promise<never> => { throw new Error('Multi-currency reporting is unavailable in demo mode') }

const demoApiProxy = {
  getSession: () => loadDemoApi().then((adapter) => adapter.getSession()),
  login: (username: string, password: string) => loadDemoApi().then((adapter) => adapter.login(username, password)),
  logout: () => loadDemoApi().then((adapter) => adapter.logout()),
  getDashboard: () => loadDemoApi().then((adapter) => adapter.getDashboard()),
  getPortfolioPerformance: async (_range: Parameters<typeof liveApi.getPortfolioPerformance>[0]) => { throw new Error('Realtime performance is unavailable in demo mode') },
  getInstruments: () => loadDemoApi().then((adapter) => adapter.getInstruments()),
  searchInstruments: (query: string) => loadDemoApi().then((adapter) => adapter.searchInstruments(query)),
  getInstrument: (symbol: string) => loadDemoApi().then((adapter) => adapter.getInstrument(symbol)),
  trackInstrument: (symbol: string) => loadDemoApi().then((adapter) => adapter.trackInstrument(symbol)),
  untrackInstrument: (symbol: string) => loadDemoApi().then((adapter) => adapter.untrackInstrument(symbol)),
  syncInstrument: (symbol: string) => loadDemoApi().then((adapter) => adapter.syncInstrument(symbol)),
  getQuote: (symbol: string) => loadDemoApi().then((adapter) => adapter.getQuote(symbol)),
  getMetrics: (symbol: string) => loadDemoApi().then((adapter) => adapter.getMetrics(symbol)),
  getPrices: (symbol: string, range: string) => loadDemoApi().then((adapter) => adapter.getPrices(symbol, range)),
  getPlans: () => loadDemoApi().then((adapter) => adapter.getPlans()),
  getPlan: (id: string) => loadDemoApi().then((adapter) => adapter.getPlan(id)),
  createPlan: (input: Parameters<typeof liveApi.createPlan>[0]) => loadDemoApi().then((adapter) => adapter.createPlan(input)),
  updatePlan: (id: string, patch: Parameters<typeof liveApi.updatePlan>[1]) => loadDemoApi().then((adapter) => adapter.updatePlan(id, patch)),
  getCycles: (id: string) => loadDemoApi().then((adapter) => adapter.getCycles(id)),
  getRecommendation: (id: string) => loadDemoApi().then((adapter) => adapter.getRecommendation(id)),
  getContributionAnalysis: (id: string) => loadDemoApi().then((adapter) => adapter.getContributionAnalysis(id)),
  previewContributionClassifications: (id: string, items: Parameters<typeof liveApi.previewContributionClassifications>[1]) => loadDemoApi().then((adapter) => adapter.previewContributionClassifications(id, items)),
  commitContributionClassifications: (id: string, previewHash: string, items: Parameters<typeof liveApi.commitContributionClassifications>[2]) => loadDemoApi().then((adapter) => adapter.commitContributionClassifications(id, previewHash, items)),
  getTransactions: () => loadDemoApi().then((adapter) => adapter.getTransactions()),
  createTransaction: (input: Parameters<typeof liveApi.createTransaction>[0]) => loadDemoApi().then((adapter) => adapter.createTransaction(input)),
  updateTransaction: (id: string, input: Parameters<typeof liveApi.updateTransaction>[1]) => loadDemoApi().then((adapter) => adapter.updateTransaction(id, input)),
  previewTransactionImport: (csv: string) => loadDemoApi().then((adapter) => adapter.previewTransactionImport(csv)),
  commitTransactionImport: (preview: Parameters<typeof liveApi.commitTransactionImport>[0]) => loadDemoApi().then((adapter) => adapter.commitTransactionImport(preview)),
  deleteTransaction: (id: string) => loadDemoApi().then((adapter) => adapter.deleteTransaction(id)),
  getSettings: () => loadDemoApi().then((adapter) => adapter.getSettings()),
  updateSettings: (patch: Parameters<typeof liveApi.updateSettings>[0]) => loadDemoApi().then((adapter) => adapter.updateSettings(patch)),
  getFunds: () => emptyDemoResult([]),
  lookupFund: (_code: string) => fundDemoUnavailable(),
  createFund: (_input: Parameters<typeof liveApi.createFund>[0]) => fundDemoUnavailable(),
  updateFund: (_id: string, _input: Parameters<typeof liveApi.updateFund>[1]) => fundDemoUnavailable(),
  getFundNav: (_id: string) => emptyDemoResult([]),
  putFundNav: (_id: string, _navDate: string, _nav: string) => fundDemoUnavailable(),
  syncFund: (_id: string, _startDate?: string, _endDate?: string) => fundDemoUnavailable(),
  getFundCalendar: (_id: string, _startDate?: string, _endDate?: string) => fundDemoUnavailable(),
  getAutoDcaRules: () => emptyDemoResult([]),
  createAutoDcaRule: (_input: Parameters<typeof liveApi.createAutoDcaRule>[0]) => fundDemoUnavailable(),
  updateAutoDcaRule: (_id: string, _input: Parameters<typeof liveApi.updateAutoDcaRule>[1]) => fundDemoUnavailable(),
  getAutoDcaProjection: (_id: string, _groupBy: 'MONTH' | 'YEAR', _includeDaily?: boolean) => fundDemoUnavailable(),
  getMultiCurrencyReport: (_range: Parameters<typeof liveApi.getMultiCurrencyReport>[0]) => reportingDemoUnavailable(),
  syncUsdCny: () => reportingDemoUnavailable(),
} satisfies typeof liveApi

export const api: typeof liveApi = APP_MODE === 'demo' ? demoApiProxy : liveApi
