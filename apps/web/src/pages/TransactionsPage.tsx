import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDownLeft, ArrowUpRight, FileUp, Pencil, Plus, Trash2, Upload, X } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { decimal, formatDate, formatMoney, formatShares } from '../lib/format'
import { parseTransactionCsv } from '../lib/fixtures'
import type { PlanCycle, Transaction, TransactionImportPreview, TransactionInput, TransactionType } from '../types'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'

const decimalPattern = /^\d+(?:\.\d{1,6})?$/
const quantityPattern = /^\d+(?:\.\d{1,8})?$/
const transactionSchema = z.object({
  tradeDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date is required.'),
  transactionType: z.enum(['BUY', 'SELL', 'DIVIDEND', 'FEE']),
  instrumentSymbol: z.string().trim().regex(/^[A-Za-z][A-Za-z0-9.-]{0,9}$/, 'Symbol is required.'),
  quantity: z.string().optional(),
  unitPrice: z.string().optional(),
  amount: z.string().optional(),
  fee: z.string().regex(/^\d*(?:\.\d{0,6})?$/, 'Enter a valid fee.'),
  notes: z.string().optional(),
  planCycleId: z.string().optional(),
}).superRefine((values, context) => {
  if (values.transactionType === 'BUY' || values.transactionType === 'SELL') {
    if (!values.quantity || !quantityPattern.test(values.quantity) || decimal(values.quantity).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['quantity'], message: 'Quantity is required and supports up to 8 decimals.' })
    if (!values.unitPrice || !decimalPattern.test(values.unitPrice) || decimal(values.unitPrice).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['unitPrice'], message: 'Unit price is required and supports up to 6 decimals.' })
  }
  if (values.transactionType === 'DIVIDEND' || values.transactionType === 'FEE') {
    if (!values.amount || !decimalPattern.test(values.amount) || decimal(values.amount).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['amount'], message: 'Amount is required and supports up to 6 decimals.' })
    if (values.fee && decimal(values.fee).gt(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['fee'], message: 'Use amount for DIVIDEND and FEE transactions.' })
  }
})

type TransactionFormValues = z.infer<typeof transactionSchema>

const initialForm: TransactionFormValues = { tradeDate: '2026-08-27', transactionType: 'BUY', instrumentSymbol: 'VOO', quantity: '', unitPrice: '', amount: '', fee: '0.00', notes: '', planCycleId: '' }

function transactionValues(transaction?: Transaction): TransactionFormValues {
  if (!transaction) return initialForm
  return { tradeDate: transaction.tradeDate, transactionType: transaction.transactionType, instrumentSymbol: transaction.instrumentSymbol, quantity: transaction.quantity ?? '', unitPrice: transaction.unitPrice ?? '', amount: transaction.amount ?? '', fee: transaction.fee, notes: transaction.notes ?? '', planCycleId: transaction.planCycleId ?? '' }
}

function transactionInput(values: TransactionFormValues): TransactionInput {
  const trade = values.transactionType === 'BUY' || values.transactionType === 'SELL'
  return {
    tradeDate: values.tradeDate,
    transactionType: values.transactionType,
    instrumentSymbol: values.instrumentSymbol.toUpperCase(),
    quantity: trade ? values.quantity || undefined : undefined,
    unitPrice: trade ? values.unitPrice || undefined : undefined,
    amount: trade ? undefined : values.amount || undefined,
    fee: values.fee || '0',
    currency: 'USD',
    notes: values.notes?.trim() || undefined,
    planCycleId: values.planCycleId || null,
  }
}

function transactionTotal(transaction: Transaction): string {
  if (transaction.total) return transaction.total
  if (transaction.quantity && transaction.unitPrice) {
    const gross = decimal(transaction.quantity).mul(transaction.unitPrice)
    return gross.plus(transaction.transactionType === 'BUY' ? transaction.fee : transaction.transactionType === 'SELL' ? decimal(transaction.fee).neg() : 0).toFixed(6)
  }
  return transaction.amount ?? '0'
}

function TransactionTypeIcon({ type }: { type: TransactionType }) {
  if (type === 'BUY') return <span className="transaction-icon transaction-buy"><ArrowDownLeft size={14} /></span>
  if (type === 'SELL') return <span className="transaction-icon transaction-sell"><ArrowUpRight size={14} /></span>
  return <span className="transaction-icon transaction-neutral"><FileUp size={14} /></span>
}

function TransactionModal({ transaction, cycles, onClose, onSaved }: { transaction?: Transaction; cycles: PlanCycle[]; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const form = useForm<TransactionFormValues>({ resolver: zodResolver(transactionSchema), defaultValues: transactionValues(transaction), mode: 'onBlur' })
  const type = form.watch('transactionType')
  const save = useMutation({ mutationFn: (values: TransactionFormValues) => transaction ? api.updateTransaction(transaction.id, transactionInput(values)) : api.createTransaction(transactionInput(values)), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['transactions'] }); void queryClient.invalidateQueries({ queryKey: ['dashboard'] }); onSaved() } })
  const errorMessage = save.error instanceof Error ? save.error.message : undefined
  return <div className="modal-backdrop"><div className="modal modal-form" role="dialog" aria-modal="true" aria-labelledby="transaction-modal-title"><button className="modal-close icon-button" onClick={onClose} aria-label={t('common.close')}><X size={17} /></button><span className="modal-icon modal-icon-blue"><ArrowDownLeft size={18} /></span><h2 id="transaction-modal-title">{transaction ? t('transactions.editTransaction') : t('transactions.addTransaction')}</h2><p>Capture a trade or cash event in the portfolio ledger.</p><form onSubmit={form.handleSubmit((values) => save.mutate(values))} className="form-stack">
    <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-date">{t('transactions.date')}</label><input id="transaction-date" type="date" {...form.register('tradeDate')} />{form.formState.errors.tradeDate ? <small className="field-error">{form.formState.errors.tradeDate.message}</small> : null}</div><div className="form-field"><label htmlFor="transaction-type">{t('transactions.type')}</label><select id="transaction-type" {...form.register('transactionType')}><option value="BUY">BUY</option><option value="SELL">SELL</option><option value="DIVIDEND">DIVIDEND</option><option value="FEE">FEE</option></select></div></div>
    <div className="form-field"><label htmlFor="transaction-symbol">{t('etfs.ticker')}</label><input id="transaction-symbol" placeholder="VOO" {...form.register('instrumentSymbol')} />{form.formState.errors.instrumentSymbol ? <small className="field-error">{form.formState.errors.instrumentSymbol.message}</small> : null}</div>
    {type === 'BUY' || type === 'SELL' ? <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-quantity">{t('transactions.quantity')}</label><input id="transaction-quantity" inputMode="decimal" placeholder="1.238423" {...form.register('quantity')} />{form.formState.errors.quantity ? <small className="field-error">{form.formState.errors.quantity.message}</small> : null}</div><div className="form-field"><label htmlFor="transaction-price">{t('transactions.unitPrice')}</label><div className="input-prefix"><span>$</span><input id="transaction-price" inputMode="decimal" placeholder="520.45" {...form.register('unitPrice')} /></div>{form.formState.errors.unitPrice ? <small className="field-error">{form.formState.errors.unitPrice.message}</small> : null}</div></div> : <div className="form-field"><label htmlFor="transaction-amount">{t('transactions.amount')}</label><div className="input-prefix"><span>$</span><input id="transaction-amount" inputMode="decimal" placeholder="42.18" {...form.register('amount')} /></div>{form.formState.errors.amount ? <small className="field-error">{form.formState.errors.amount.message}</small> : null}</div>}
    <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-fee">{t('transactions.fee')}</label><div className="input-prefix"><span>$</span><input id="transaction-fee" inputMode="decimal" {...form.register('fee')} /></div>{form.formState.errors.fee ? <small className="field-error">{form.formState.errors.fee.message}</small> : null}</div><div className="form-field"><label htmlFor="transaction-cycle">{t('transactions.planCycle')}</label><select id="transaction-cycle" {...form.register('planCycleId')}><option value="">Outside a plan</option>{cycles.map((cycle) => <option key={cycle.id} value={cycle.id}>{formatDate(`${cycle.period}-01`)} </option>)}</select></div></div>
    <div className="form-field"><label htmlFor="transaction-notes">{t('transactions.notes')}</label><textarea id="transaction-notes" rows={2} placeholder="Optional note" {...form.register('notes')} /></div>
    {errorMessage ? <p className="form-alert" role="alert">{errorMessage}</p> : null}
    <div className="form-footer"><button type="button" className="button button-ghost" onClick={onClose}>{t('common.cancel')}</button><button type="submit" className="button button-primary" disabled={save.isPending}><Plus size={15} />{save.isPending ? 'Saving...' : transaction ? t('common.save') : t('transactions.saveTransaction')}</button></div>
  </form></div></div>
}

function CsvModal({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [csv, setCsv] = useState('date,type,symbol,quantity,price,fee\n2026-09-01,BUY,VOO,1.2,620.00,0\n2026-09-01,BUY,QQQ,0.8,574.00,0')
  const [preview, setPreview] = useState<TransactionImportPreview | null>(null)
  const localParse = useMemo(() => parseTransactionCsv(csv), [csv])
  const previewMutation = useMutation({ mutationFn: () => api.previewTransactionImport(csv), onSuccess: (result) => setPreview(result.data) })
  const importMutation = useMutation({ mutationFn: () => api.commitTransactionImport(preview as TransactionImportPreview), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['transactions'] }); void queryClient.invalidateQueries({ queryKey: ['dashboard'] }); onImported() } })
  const currentPreview = preview?.rows.length || preview?.errors.length ? preview : { batchId: '', rows: localParse.rows, errors: localParse.errors }
  const duplicateRows = currentPreview.duplicateRows ?? []
  const hasPreviewErrors = currentPreview.errors.length > 0 || duplicateRows.length > 0
  const previewError = previewMutation.error instanceof Error ? previewMutation.error.message : importMutation.error instanceof Error ? importMutation.error.message : undefined
  return <div className="modal-backdrop"><div className="modal modal-wide modal-csv" role="dialog" aria-modal="true" aria-labelledby="csv-title"><button type="button" className="modal-close icon-button" onClick={onClose} aria-label={t('common.close')}><X size={17} /></button><span className="modal-icon modal-icon-blue"><Upload size={18} /></span><h2 id="csv-title">{t('transactions.csvTitle')}</h2><p>{t('transactions.csvHint')}</p><textarea className="csv-editor" rows={6} value={csv} onChange={(event) => { setCsv(event.target.value); setPreview(null) }} aria-label="CSV input" />{hasPreviewErrors ? <div className="csv-errors">{currentPreview.errors.map((error) => <span key={error}>{error}</span>)}{duplicateRows.length ? <span>Duplicate rows: {duplicateRows.join(', ')}</span> : null}</div> : <div className="csv-preview"><div className="csv-preview-header"><span>{t('transactions.preview')}</span><strong>{currentPreview.rows.length} valid rows</strong></div>{currentPreview.rows.slice(0, 4).map((row, index) => <div className="csv-preview-row" key={`${row.instrumentSymbol}-${index}`}><span>{row.tradeDate}</span><span className="ticker-chip">{row.instrumentSymbol}</span><span>{row.transactionType}</span><span>{row.quantity ? `${formatShares(row.quantity)} @ ${formatMoney(row.unitPrice)}` : formatMoney(row.amount)}</span></div>)}</div>}{previewError ? <p className="form-alert" role="alert">{previewError}</p> : null}<div className="form-footer"><button type="button" className="button button-secondary" onClick={() => previewMutation.mutate()} disabled={previewMutation.isPending || localParse.errors.length > 0}>{previewMutation.isPending ? 'Validating...' : 'Validate CSV'}</button><button type="button" className="button button-primary" onClick={() => importMutation.mutate()} disabled={!preview || hasPreviewErrors || preview.rows.length === 0 || importMutation.isPending}><Upload size={15} />{t('transactions.importRows', { count: preview?.rows.length ?? 0 })}</button></div></div></div>
}

function TransactionRow({ transaction, onDelete, onEdit }: { transaction: Transaction; onDelete: (id: string) => void; onEdit: (transaction: Transaction) => void }) {
  const { t } = useTranslation()
  const canShowQuantity = Boolean(transaction.quantity && transaction.unitPrice)
  const label = transaction.transactionType.toLowerCase()
  return <tr><td><span className="table-ticker"><TransactionTypeIcon type={transaction.transactionType} /><span><strong>{transaction.instrumentSymbol}</strong><small>{transaction.notes ?? t(`transactions.${label}`, { defaultValue: transaction.transactionType })}</small></span></span></td><td>{formatDate(transaction.tradeDate)}</td><td><span className={`type-label type-${label}`}>{transaction.transactionType}</span></td><td>{canShowQuantity ? <><strong>{formatShares(transaction.quantity)}</strong><small className="table-subline">@ {formatMoney(transaction.unitPrice)}</small></> : '—'}</td><td><strong>{formatMoney(transactionTotal(transaction))}</strong>{decimal(transaction.fee).gt(0) ? <small className="table-subline">fee {formatMoney(transaction.fee)}</small> : null}</td><td><div className="row-actions"><button className="icon-button subtle-icon" aria-label={t('common.edit')} onClick={() => onEdit(transaction)}><Pencil size={14} /></button><button className="icon-button subtle-icon danger-icon" onClick={() => onDelete(transaction.id)} aria-label={t('common.delete')}><Trash2 size={14} /></button></div></td></tr>
}

export function TransactionsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [csvOpen, setCsvOpen] = useState(false)
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [filter, setFilter] = useState<'ALL' | TransactionType>('ALL')
  const [search, setSearch] = useState('')
  const transactions = useQuery({ queryKey: ['transactions'], queryFn: api.getTransactions })
  const plans = useQuery({ queryKey: ['plans'], queryFn: api.getPlans })
  const activePlan = plans.data?.data.find((plan) => plan.status === 'ACTIVE')
  const cycles = useQuery({ queryKey: ['transaction-cycles', activePlan?.id], queryFn: () => api.getCycles(activePlan?.id ?? ''), enabled: Boolean(activePlan) })
  const remove = useMutation({ mutationFn: (id: string) => api.deleteTransaction(id), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['transactions'] }); void queryClient.invalidateQueries({ queryKey: ['dashboard'] }) } })
  const filtered = useMemo(() => (transactions.data?.data ?? []).filter((transaction) => (filter === 'ALL' || transaction.transactionType === filter) && (!search || transaction.instrumentSymbol.toLowerCase().includes(search.toLowerCase()))), [filter, search, transactions.data?.data])
  const modalCycles = cycles.data?.data ?? activePlan?.cycles ?? []
  return <div className="page transactions-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('transactions.eyebrow')}</span><h1>{t('transactions.title')}</h1><p>{t('transactions.subtitle')}</p></div><div className="page-actions"><button className="button button-secondary" onClick={() => setCsvOpen(true)}><FileUp size={15} />{t('transactions.importCsv')}</button><button className="button button-primary" onClick={() => { setEditing(null); setAddOpen(true) }}><Plus size={16} />{t('transactions.addTransaction')}</button></div></div>
    <DataStateBanner status={transactions.data?.meta.status ?? (transactions.isError ? 'UNAVAILABLE' : 'STALE')} message={transactions.data?.meta.message} source={transactions.data?.meta.source === 'FIXTURE' ? t('common.demoData') : transactions.data?.meta.source} asOf={transactions.data?.meta.asOf} retrievedAt={transactions.data?.meta.retrievedAt} />
    <Panel className="transactions-panel" flush><div className="table-toolbar"><div><strong>{transactions.data?.data.length ?? 0}</strong><span> ledger entries</span></div><div className="table-toolbar-actions"><label className="inline-search"><span className="sr-only">Search symbol</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Filter symbol" /></label><select value={filter} onChange={(event) => setFilter(event.target.value as 'ALL' | TransactionType)} aria-label="Filter transaction type"><option value="ALL">All types</option><option value="BUY">BUY</option><option value="SELL">SELL</option><option value="DIVIDEND">DIVIDEND</option><option value="FEE">FEE</option></select></div></div>{transactions.isLoading ? <div className="table-loading"><LoadingBlock lines={7} /></div> : transactions.isError ? <ErrorState onRetry={() => void transactions.refetch()} /> : filtered.length ? <div className="data-table-wrap"><table className="data-table transactions-table"><thead><tr><th>{t('transactions.transaction')}</th><th>{t('transactions.date')}</th><th>{t('transactions.type')}</th><th>{t('transactions.quantity')}</th><th>{t('transactions.total')}</th><th /></tr></thead><tbody>{filtered.map((transaction) => <TransactionRow key={transaction.id} transaction={transaction} onDelete={(id) => remove.mutate(id)} onEdit={(value) => { setEditing(value); setAddOpen(true) }} />)}</tbody></table></div> : <EmptyState title={t('transactions.noTransactions')} detail={t('transactions.sourceHint')} action={<button className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={15} />{t('transactions.addTransaction')}</button>} />}</Panel>
    {remove.error instanceof Error ? <p className="form-alert page-alert" role="alert">{remove.error.message}</p> : null}
    <div className="ledger-note"><span className="status-dot status-dot-blue" /><span>Transactions are the source of truth. Holdings and portfolio snapshots are calculated projections.</span></div>
    {addOpen ? <TransactionModal transaction={editing ?? undefined} cycles={modalCycles} onClose={() => { setAddOpen(false); setEditing(null) }} onSaved={() => { setAddOpen(false); setEditing(null) }} /> : null}{csvOpen ? <CsvModal onClose={() => setCsvOpen(false)} onImported={() => setCsvOpen(false)} /> : null}
  </div>
}
