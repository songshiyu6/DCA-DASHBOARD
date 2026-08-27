import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient, useQueries } from '@tanstack/react-query'
import { ArrowUpRight, Check, ChevronRight, Plus, Search, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { decimal, formatCompactMoney, formatMoney, formatPercent, formatSignedPercent } from '../lib/format'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import { StatusBadge } from '../components/StatusBadge'
import type { Instrument } from '../types'

function EtfCard({ instrument, quote, onOpen }: { instrument: Instrument; quote?: { price: string; changePercent: string; status?: 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE' }; onOpen: () => void }) {
  const { t } = useTranslation()
  return <button className="etf-card" onClick={onOpen}><div className="etf-card-top"><span className="ticker-avatar ticker-avatar-wide">{instrument.symbol.slice(0, 2)}</span><span className="etf-card-title"><strong>{instrument.symbol}</strong><small>{instrument.name}</small></span><ChevronRight size={16} /></div><div className="etf-card-quote"><strong>{quote ? formatMoney(quote.price) : '—'}</strong>{quote ? <span className={decimal(quote.changePercent).lt(0) ? 'trend-negative' : decimal(quote.changePercent).gt(0) ? 'trend-positive' : 'trend-flat'}>{formatSignedPercent(quote.changePercent)}</span> : <span>—</span>}</div><div className="etf-card-footer"><span>{instrument.exchange} · {instrument.currency}</span>{quote ? <StatusBadge status={quote.status ?? 'UNAVAILABLE'} compact /> : <span>{t('common.loading')}</span>}</div></button>
}

export function EtfsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [search, setSearch] = useState('')
  const instruments = useQuery({ queryKey: ['instruments'], queryFn: api.getInstruments })
  const quotes = useQueries({ queries: (instruments.data?.data ?? []).map((instrument) => ({ queryKey: ['quote', instrument.symbol], queryFn: () => api.getQuote(instrument.symbol), staleTime: 60_000 })) })
  const searchResults = useQuery({ queryKey: ['instrument-search', search], queryFn: () => api.searchInstruments(search), enabled: addOpen && search.trim().length > 0 })
  const track = useMutation({ mutationFn: (symbol: string) => api.trackInstrument(symbol), onSuccess: (result) => { void queryClient.invalidateQueries({ queryKey: ['instruments'] }); setSearch(result.data.symbol); setAddOpen(false) } })

  const quoteBySymbol = useMemo(() => Object.fromEntries(quotes.map((query, index) => [instruments.data?.data[index]?.symbol, query.data?.data])), [quotes, instruments.data?.data])
  const tracked = instruments.data?.data ?? []
  return <div className="page etfs-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('etfs.eyebrow')}</span><h1>{t('etfs.title')}</h1><p>{t('etfs.subtitle')}</p></div><button className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={16} />{t('etfs.addEtf')}</button></div>
    <DataStateBanner status={instruments.data?.meta.status ?? (instruments.isError ? 'UNAVAILABLE' : 'STALE')} message={instruments.data?.meta.message} source={instruments.data?.meta.source === 'FIXTURE' ? t('common.demoData') : instruments.data?.meta.source} asOf={instruments.data?.meta.asOf} retrievedAt={instruments.data?.meta.retrievedAt} />
    {instruments.isLoading ? <div className="etf-card-grid">{[1, 2, 3].map((item) => <Panel key={item}><LoadingBlock lines={4} /></Panel>)}</div> : instruments.isError ? <ErrorState onRetry={() => void instruments.refetch()} /> : tracked.length === 0 ? <EmptyState title={t('etfs.noTracked')} action={<button className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={15} />{t('etfs.addEtf')}</button>} /> : <><SectionToolbar count={tracked.length} /><div className="etf-card-grid">{tracked.map((instrument, index) => <EtfCard key={instrument.symbol} instrument={instrument} quote={quoteBySymbol[instrument.symbol] ?? quotes[index]?.data?.data} onOpen={() => navigate(`/etfs/${instrument.symbol}`)} />)}</div><Panel title={t('etfs.tracked')} detail="Open a fund for its full metrics and history" className="etf-table-panel" flush><div className="data-table-wrap"><table className="data-table"><thead><tr><th>{t('etfs.ticker')}</th><th>{t('etfs.issuer')}</th><th>{t('etfs.price')}</th><th>1D</th><th>{t('etfs.expenseRatio')}</th><th>{t('etfs.aum')}</th><th /></tr></thead><tbody>{tracked.map((instrument, index) => { const quote = quoteBySymbol[instrument.symbol] ?? quotes[index]?.data?.data; return <tr key={instrument.symbol} tabIndex={0} onClick={() => navigate(`/etfs/${instrument.symbol}`)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); navigate(`/etfs/${instrument.symbol}`) } }}><td><span className="table-ticker"><span className="ticker-avatar">{instrument.symbol.slice(0, 1)}</span><span><strong>{instrument.symbol}</strong><small>{instrument.name}</small></span></span></td><td>{instrument.issuer}</td><td><strong>{quote ? formatMoney(quote.price) : '—'}</strong></td><td className={quote && decimal(quote.changePercent).lt(0) ? 'text-negative' : quote && decimal(quote.changePercent).gt(0) ? 'text-positive' : 'trend-flat'}>{quote ? formatSignedPercent(quote.changePercent) : '—'}</td><td>{instrument.expenseRatio ? formatPercent(instrument.expenseRatio) : '—'}</td><td>{instrument.aum ? formatCompactMoney(instrument.aum) : '—'}</td><td><ChevronRight size={16} className="table-chevron" /></td></tr> })}</tbody></table></div></Panel></>}
    {addOpen ? <div className="modal-backdrop" role="presentation"><div className="modal modal-wide" role="dialog" aria-modal="true" aria-labelledby="add-etf-title"><button type="button" className="modal-close icon-button" onClick={() => setAddOpen(false)} aria-label={t('common.close')}><X size={17} /></button><h2 id="add-etf-title">{t('etfs.addTitle')}</h2><p>{t('etfs.addHint')}</p><label className="search-field modal-search"><Search size={16} /><input autoFocus value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search by ticker or fund name" /></label><div className="search-result-list">{searchResults.isFetching ? <LoadingBlock lines={3} /> : searchResults.isError ? <ErrorState message={searchResults.error instanceof Error ? searchResults.error.message : undefined} onRetry={() => void searchResults.refetch()} /> : searchResults.data?.data.length ? searchResults.data.data.map((instrument) => <div className="search-result" key={instrument.symbol}><span className="ticker-avatar">{instrument.symbol.slice(0, 1)}</span><span><strong>{instrument.symbol}</strong><small>{instrument.name} · {instrument.exchange}</small></span>{instrument.tracked ? <span className="already-tracked"><Check size={14} />Tracked</span> : <button type="button" className="button button-secondary button-small" onClick={() => track.mutate(instrument.symbol)} disabled={track.isPending}><Plus size={14} />{t('common.add')}</button>}</div>) : search.trim() ? <EmptyState title={t('etfs.noResults')} /> : <div className="search-prompt"><Search size={18} /><span>Try VOO, QQQ, or SCHD</span></div>}</div></div></div> : null}
  </div>
}

function SectionToolbar({ count }: { count: number }) {
  const { t } = useTranslation()
  return <div className="section-toolbar"><span><strong>{count}</strong> {t('etfs.tracked').toLowerCase()}</span><span className="toolbar-note"><ArrowUpRight size={14} />Live quote cache · 60 sec</span></div>
}
