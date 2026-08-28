import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient, useQueries } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, ArrowUpRight, Check, ChevronRight, Plus, Search, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { decimal, formatCompactMoney, formatMoney, formatPercent, formatSignedPercent } from '../lib/format'
import { invalidateInstrumentQueries, queryKeys } from '../lib/queryKeys'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Dialog } from '../components/Dialog'
import { Panel } from '../components/Panel'
import { StatusBadge } from '../components/StatusBadge'
import type { Instrument, Quote } from '../types'

const ETF_ORDER_STORAGE_KEY = 'dca-terminal:tracked-etf-order'

function loadEtfOrder(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const value = JSON.parse(window.localStorage.getItem(ETF_ORDER_STORAGE_KEY) ?? '[]')
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

function saveEtfOrder(order: string[]) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(ETF_ORDER_STORAGE_KEY, JSON.stringify(order))
}

function EtfCard({ instrument, quote, canMoveUp, canMoveDown, onMoveUp, onMoveDown, onOpen }: {
  instrument: Instrument
  quote?: Pick<Quote, 'price' | 'changePercent' | 'status'>
  canMoveUp: boolean
  canMoveDown: boolean
  onMoveUp: () => void
  onMoveDown: () => void
  onOpen: () => void
}) {
  const { t } = useTranslation()
  return <article className="etf-card">
    <button type="button" className="etf-card-open" onClick={onOpen}>
      <div className="etf-card-top"><span className="ticker-avatar ticker-avatar-wide">{instrument.symbol.slice(0, 2)}</span><span className="etf-card-title"><strong>{instrument.symbol}</strong><small>{instrument.name}</small></span><ChevronRight size={16} /></div>
      <div className="etf-card-quote"><strong>{quote ? formatMoney(quote.price) : '—'}</strong>{quote ? <span className={decimal(quote.changePercent).lt(0) ? 'trend-negative' : decimal(quote.changePercent).gt(0) ? 'trend-positive' : 'trend-flat'}>{formatSignedPercent(quote.changePercent)}</span> : <span>—</span>}</div>
    </button>
    <div className="etf-card-footer"><span>{instrument.exchange} · {instrument.currency}</span><span className="etf-card-footer-actions"><span className="etf-order-controls"><button type="button" className="icon-button etf-order-button" onClick={onMoveUp} disabled={!canMoveUp} aria-label={`Move ${instrument.symbol} up`} title={`Move ${instrument.symbol} up`}><ArrowUp size={14} /></button><button type="button" className="icon-button etf-order-button" onClick={onMoveDown} disabled={!canMoveDown} aria-label={`Move ${instrument.symbol} down`} title={`Move ${instrument.symbol} down`}><ArrowDown size={14} /></button></span>{quote ? <StatusBadge status={quote.status ?? 'UNAVAILABLE'} compact /> : <span>{t('common.loading')}</span>}</span></div>
  </article>
}

export function EtfsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [preferredOrder, setPreferredOrder] = useState<string[]>(loadEtfOrder)
  const instruments = useQuery({ queryKey: queryKeys.instruments, queryFn: api.getInstruments })
  const quotes = useQueries({ queries: (instruments.data?.data ?? []).map((instrument) => ({ queryKey: queryKeys.quote(instrument.symbol), queryFn: () => api.getQuote(instrument.symbol), staleTime: 60_000 })) })
  const searchResults = useQuery({ queryKey: queryKeys.instrumentSearch(search), queryFn: () => api.searchInstruments(search), enabled: addOpen && search.trim().length > 0 })
  const track = useMutation({ mutationFn: (symbol: string) => api.trackInstrument(symbol), onSuccess: (result) => { void invalidateInstrumentQueries(queryClient); setSearch(result.data.symbol); setAddOpen(false) } })

  const quoteBySymbol = useMemo(() => Object.fromEntries(quotes.map((query, index) => [instruments.data?.data[index]?.symbol, query.data?.data])), [quotes, instruments.data?.data])
  const tracked = useMemo(() => {
    const source = instruments.data?.data ?? []
    const bySymbol = new Map(source.map((instrument) => [instrument.symbol, instrument]))
    const ordered = preferredOrder.map((symbol) => bySymbol.get(symbol)).filter((instrument): instrument is Instrument => Boolean(instrument))
    const alreadyOrdered = new Set(ordered.map((instrument) => instrument.symbol))
    return [...ordered, ...source.filter((instrument) => !alreadyOrdered.has(instrument.symbol))]
  }, [instruments.data?.data, preferredOrder])

  const moveTracked = (symbol: string, offset: -1 | 1) => {
    const order = tracked.map((instrument) => instrument.symbol)
    const from = order.indexOf(symbol)
    const to = from + offset
    if (from < 0 || to < 0 || to >= order.length) return
    ;[order[from], order[to]] = [order[to], order[from]]
    saveEtfOrder(order)
    setPreferredOrder(order)
  }

  return <div className="page etfs-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('etfs.eyebrow')}</span><h1>{t('etfs.title')}</h1><p>{t('etfs.subtitle')}</p></div><button type="button" className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={16} />{t('etfs.addEtf')}</button></div>
    <DataStateBanner status={instruments.data?.meta.status ?? (instruments.isError ? 'UNAVAILABLE' : 'STALE')} message={instruments.data?.meta.message} source={instruments.data?.meta.source === 'FIXTURE' ? t('common.demoData') : instruments.data?.meta.source} asOf={instruments.data?.meta.asOf} retrievedAt={instruments.data?.meta.retrievedAt} />
    {instruments.isLoading ? <div className="etf-card-grid">{[1, 2, 3].map((item) => <Panel key={item}><LoadingBlock lines={4} /></Panel>)}</div> : instruments.isError ? <ErrorState onRetry={() => void instruments.refetch()} /> : tracked.length === 0 ? <EmptyState title={t('etfs.noTracked')} action={<button type="button" className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={15} />{t('etfs.addEtf')}</button>} /> : <><SectionToolbar count={tracked.length} /><div className="etf-card-grid">{tracked.map((instrument, index) => <EtfCard key={instrument.symbol} instrument={instrument} quote={quoteBySymbol[instrument.symbol]} canMoveUp={index > 0} canMoveDown={index < tracked.length - 1} onMoveUp={() => moveTracked(instrument.symbol, -1)} onMoveDown={() => moveTracked(instrument.symbol, 1)} onOpen={() => navigate(`/etfs/${instrument.symbol}`)} />)}</div><Panel title={t('etfs.tracked')} detail={t('etfs.tableHint')} className="etf-table-panel" flush><div className="data-table-wrap"><table className="data-table"><thead><tr><th scope="col">{t('etfs.ticker')}</th><th scope="col">{t('etfs.issuer')}</th><th scope="col">{t('etfs.price')}</th><th scope="col">1D</th><th scope="col">{t('etfs.expenseRatio')}</th><th scope="col">{t('etfs.aum')}</th><th scope="col" aria-label={t('etfs.openDetails')} /></tr></thead><tbody>{tracked.map((instrument) => { const quote = quoteBySymbol[instrument.symbol]; return <tr key={instrument.symbol}><td><button type="button" className="table-row-button" onClick={() => navigate(`/etfs/${instrument.symbol}`)} aria-label={t('etfs.openDetailsFor', { symbol: instrument.symbol })}><span className="table-ticker"><span className="ticker-avatar">{instrument.symbol.slice(0, 1)}</span><span><strong>{instrument.symbol}</strong><small>{instrument.name}</small></span></span></button></td><td>{instrument.issuer}</td><td><strong>{quote ? formatMoney(quote.price) : '—'}</strong></td><td className={quote && decimal(quote.changePercent).lt(0) ? 'text-negative' : quote && decimal(quote.changePercent).gt(0) ? 'text-positive' : 'trend-flat'}>{quote ? formatSignedPercent(quote.changePercent) : '—'}</td><td>{instrument.expenseRatio ? formatPercent(instrument.expenseRatio) : '—'}</td><td>{instrument.aum ? formatCompactMoney(instrument.aum) : '—'}</td><td><ChevronRight size={16} className="table-chevron" aria-hidden="true" /></td></tr> })}</tbody></table></div></Panel></>}
    {addOpen ? <Dialog className="modal-wide" labelledBy="add-etf-title" describedBy="add-etf-description" onClose={() => setAddOpen(false)}><button type="button" className="modal-close icon-button" onClick={() => setAddOpen(false)} aria-label={t('common.close')}><X size={17} /></button><h2 id="add-etf-title">{t('etfs.addTitle')}</h2><p id="add-etf-description">{t('etfs.addHint')}</p><label className="search-field modal-search" htmlFor="etf-search"><Search size={16} /><span className="sr-only">{t('etfs.searchLabel')}</span><input id="etf-search" autoFocus value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t('etfs.searchPlaceholder')} /></label><div className="search-result-list">{searchResults.isFetching ? <LoadingBlock lines={3} /> : searchResults.isError ? <ErrorState message={searchResults.error instanceof Error ? searchResults.error.message : undefined} onRetry={() => void searchResults.refetch()} /> : searchResults.data?.data.length ? searchResults.data.data.map((instrument) => <div className="search-result" key={instrument.symbol}><span className="ticker-avatar">{instrument.symbol.slice(0, 1)}</span><span><strong>{instrument.symbol}</strong><small>{instrument.name} · {instrument.exchange}</small></span>{instrument.tracked ? <span className="already-tracked"><Check size={14} />{t('etfs.trackedLabel')}</span> : <button type="button" className="button button-secondary button-small" onClick={() => track.mutate(instrument.symbol)} disabled={track.isPending}><Plus size={14} />{t('common.add')}</button>}</div>) : search.trim() ? <EmptyState title={t('etfs.noResults')} /> : <div className="search-prompt"><Search size={18} /><span>{t('etfs.searchExamples')}</span></div>}</div></Dialog> : null}
  </div>
}

function SectionToolbar({ count }: { count: number }) {
  const { t } = useTranslation()
  return <div className="section-toolbar"><span><strong>{count}</strong> {t('etfs.tracked').toLowerCase()}</span><span className="toolbar-note"><ArrowUpRight size={14} />{t('etfs.quoteCache')}</span></div>
}
