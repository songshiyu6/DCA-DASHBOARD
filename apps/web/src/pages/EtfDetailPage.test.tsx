import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixtureInstruments } from '../lib/fixtures'
import type { EtfMetrics, PricePoint, Quote } from '../types'
import { EtfDetailPage, INTRADAY_REFETCH_INTERVAL_MS, INTRADAY_STALE_TIME_MS } from './EtfDetailPage'

const mockedApi = vi.hoisted(() => ({
  getInstrument: vi.fn(),
  getQuote: vi.fn(),
  getMetrics: vi.fn(),
  getPrices: vi.fn(),
  syncInstrument: vi.fn(),
  untrackInstrument: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))
vi.mock('../components/charts/PriceChart', () => ({ PriceChart: () => <div data-testid="price-chart" /> }))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/etfs/VOO']}><Routes><Route path="/etfs/:symbol" element={<EtfDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  const instrument = fixtureInstruments[0]
  const quote: Quote = { symbol: 'VOO', price: '620.21', previousClose: '617.62', change: '2.59', changePercent: '0.004193', marketTimestamp: '2026-08-27T19:58:00Z', retrievedAt: '2026-08-27T20:02:00Z', source: 'YAHOO', status: 'STALE', nav: null }
  const metrics: EtfMetrics = { oneDay: '0.004193', oneMonth: '0.032100', threeMonths: '0.081200', ytd: '0.141000', oneYear: '0.201000', threeYearCagr: '0.153000', fiftyTwoWeekHigh: '630.00', fiftyTwoWeekLow: '480.00', currentDrawdown: '-0.015000', maxDrawdown1Y: '-0.180000' }
  const prices: PricePoint[] = [{ date: '2026-08-26', high: '620', low: '610', close: '618', adjustedClose: '618' }]
  mockedApi.getInstrument.mockResolvedValue({ data: instrument, meta: { status: 'FRESH', source: 'YAHOO' } })
  mockedApi.getQuote.mockResolvedValue({ data: quote, meta: { status: 'FRESH', source: 'YAHOO' } })
  mockedApi.getMetrics.mockResolvedValue({ data: metrics, meta: { status: 'FRESH', source: 'YAHOO' } })
  mockedApi.getPrices.mockResolvedValue({ data: prices, meta: { status: 'FRESH', source: 'YAHOO' } })
  mockedApi.syncInstrument.mockResolvedValue({ data: { symbol: 'VOO', barsSaved: 0, splitsSaved: 0, status: 'FRESH', completedAt: '2026-08-27T20:02:00Z' }, meta: { status: 'FRESH', source: 'YAHOO' } })
})

afterEach(() => {
  focusManager.setFocused(undefined)
})

describe('ETF detail metrics', () => {
  it('uses a short cache and refresh interval for 1D intraday data', () => {
    expect(INTRADAY_STALE_TIME_MS).toBeLessThan(60_000)
    expect(INTRADAY_STALE_TIME_MS).toBeLessThan(86_400_000)
    expect(INTRADAY_REFETCH_INTERVAL_MS).toBe(60_000)
  })

  it('renders adjusted-performance metrics, stale status, and NAV separately from market price', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'VOO' })).toBeInTheDocument()
    expect(screen.getAllByText('Data delayed').length).toBeGreaterThan(0)
    expect(screen.getByText('$620.21')).toBeInTheDocument()
    expect(screen.getByText('$620.10')).toBeInTheDocument()
    expect(screen.getByText('+15.30%')).toBeInTheDocument()
  })

  it('offers a history sync retry when the provider returned no daily bars', async () => {
    mockedApi.getInstrument.mockResolvedValue({ data: { ...fixtureInstruments[0], dataStatus: 'INSUFFICIENT_HISTORY' }, meta: { status: 'INSUFFICIENT_HISTORY', source: 'YAHOO' } })
    mockedApi.getPrices.mockResolvedValue({ data: [], meta: { status: 'INSUFFICIENT_HISTORY', source: 'YAHOO', message: 'The provider returned no usable daily bars yet' } })

    renderPage()

    expect((await screen.findAllByText('The provider returned no usable daily bars yet')).length).toBe(2)
    fireEvent.click(screen.getAllByRole('button', { name: /Retry/i })[0])

    await waitFor(() => expect(mockedApi.syncInstrument).toHaveBeenCalledWith('VOO'))
  })

  it('retries 1D directly instead of running the daily history sync', async () => {
    mockedApi.getPrices.mockImplementation((_symbol: string, requestedRange: string) => Promise.resolve(
      requestedRange === '1D'
        ? { data: [], meta: { status: 'PARTIAL', source: 'YAHOO', message: 'Current trading session has no intraday bars yet' } }
        : { data: [{ date: '2026-08-26', close: '618', adjustedClose: '618' }], meta: { status: 'FRESH', source: 'YAHOO' } },
    ))

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: '1D' }))
    expect((await screen.findAllByText('Current trading session has no intraday bars yet')).length).toBeGreaterThan(0)

    fireEvent.click(screen.getAllByRole('button', { name: /Retry/i })[0])

    await waitFor(() => expect(mockedApi.getPrices).toHaveBeenCalledWith('VOO', '1D'))
    await waitFor(() => expect(mockedApi.getPrices.mock.calls.filter((call) => call[1] === '1D').length).toBeGreaterThanOrEqual(2))
    expect(mockedApi.syncInstrument).not.toHaveBeenCalled()
  })

  it('can refresh an overnight empty 1D result on focus and show pre-market bars', async () => {
    let intradayCalls = 0
    mockedApi.getPrices.mockImplementation((_symbol: string, requestedRange: string) => {
      if (requestedRange !== '1D') {
        return Promise.resolve({ data: [{ date: '2026-08-26', close: '618', adjustedClose: '618' }], meta: { status: 'FRESH', source: 'YAHOO' } })
      }
      intradayCalls += 1
      if (intradayCalls === 1) {
        return Promise.resolve({ data: [], meta: { status: 'PARTIAL', source: 'YAHOO', message: 'Current trading session has no intraday bars yet' } })
      }
      return Promise.resolve({
        data: [{ date: '2026-08-31T08:05:00Z', close: '619.50' }],
        meta: { status: 'FRESH', source: 'YAHOO', asOf: '2026-08-31', retrievedAt: '2026-08-31T08:06:00Z' },
      })
    })

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: '1D' }))
    expect((await screen.findAllByText('Current trading session has no intraday bars yet')).length).toBeGreaterThan(0)

    act(() => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
    })

    await waitFor(() => expect(intradayCalls).toBeGreaterThanOrEqual(2))
    expect(await screen.findByTestId('price-chart')).toBeInTheDocument()
  })
})
