import type { Instrument, InstrumentSyncResult, PricePoint, Quote, EtfMetrics } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeInstrument, normalizeInstruments, normalizeMetrics, normalizePricePoints, normalizeQuote, normalizeResult, normalizeSyncResult } from './normalize'

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
  getQuote: async (symbol: string): ApiResponse<Quote> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/quote`), normalizeQuote, apiMeta()),
  getMetrics: async (symbol: string): ApiResponse<EtfMetrics> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/metrics`), normalizeMetrics, apiMeta()),
  getPrices: async (symbol: string, range: string): ApiResponse<PricePoint[]> => normalizeResult(await request<unknown>(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/prices?range=${encodeURIComponent(range)}`), normalizePricePoints, apiMeta()),
}
