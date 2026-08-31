import type { Instrument, InstrumentSyncResult, PricePoint, Quote, QuoteSession, EtfMetrics } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { isRecord, normalizeApiResponse, normalizeInstrument, normalizeInstruments, normalizeMetrics, normalizePricePoints, normalizeQuote, normalizeResult, normalizeSyncResult } from './normalize'

function quoteSession(value: unknown): QuoteSession {
  return value === 'REGULAR' || value === 'PRE_MARKET' || value === 'EXTENDED' || value === 'POST_MARKET'
    || value === 'OVERNIGHT' || value === 'REGULAR_FALLBACK' ? value : 'UNKNOWN'
}

async function getQuote(symbol: string): ApiResponse<Quote> {
  const result = normalizeApiResponse<unknown>(
    await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/quote`),
    apiMeta(),
  )
  const quote = normalizeQuote(result.data)
  quote.quoteSession = isRecord(result.data) ? quoteSession(result.data.quoteSession) : 'UNKNOWN'
  return { ...result, data: quote }
}

export const instrumentsApi = {
  getInstruments: async (): ApiResponse<Instrument[]> => normalizeResult(await request<unknown>('/instruments'), normalizeInstruments, apiMeta()),
  searchInstruments: async (query: string): ApiResponse<Instrument[]> => normalizeResult(await request<unknown>(`/instruments/search?q=${encodeURIComponent(query)}`), normalizeInstruments, apiMeta()),
  getInstrument: async (symbol: string): ApiResponse<Instrument> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}`), normalizeInstrument, apiMeta()),
  trackInstrument: async (symbol: string): ApiResponse<Instrument> => normalizeResult(await request<unknown>('/instruments', {
    method: 'POST',
    body: JSON.stringify({ symbol: symbol.toUpperCase() }),
  }), normalizeInstrument, apiMeta()),
  untrackInstrument: async (symbol: string): ApiResponse<Instrument> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}`, { method: 'DELETE' }), normalizeInstrument, apiMeta()),
  syncInstrument: async (symbol: string): ApiResponse<InstrumentSyncResult> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/sync`, { method: 'POST' }), normalizeSyncResult, apiMeta()),
  getQuote,
  getMetrics: async (symbol: string): ApiResponse<EtfMetrics> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/metrics`), normalizeMetrics, apiMeta()),
  getPrices: async (symbol: string, range: string): ApiResponse<PricePoint[]> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/prices?range=${encodeURIComponent(range)}`), normalizePricePoints, apiMeta()),
}
