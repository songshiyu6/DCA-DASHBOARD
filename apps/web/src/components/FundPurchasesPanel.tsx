import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Edit3, Plus, Trash2, X } from 'lucide-react'
import { Dialog } from './Dialog'
import { EmptyState, ErrorState, LoadingBlock } from './DataState'
import { Panel } from './Panel'
import { api } from '../lib/api'
import { invalidateFundQueries, queryKeys } from '../lib/queryKeys'
import type { FundPurchase, FundPurchaseInput, MutualFund } from '../types'

const today = () => new Date().toISOString().slice(0, 10)
const emptyForm = () => ({ purchaseDate: today(), grossAmount: '', purchaseFeeRate: '0', notes: '' })

function copy(isZh: boolean) {
  return isZh ? {
    title: '一次性买入', add: '记录买入', empty: '还没有一次性买入记录', date: '买入日期', amount: '实际支付', nav: '成交 NAV', fee: '申购费', feeRate: '申购费率', shares: '份额', notes: '备注', actions: '操作',
    createTitle: '记录一次性买入', editTitle: '编辑一次性买入', save: '保存', cancel: '取消', edit: '编辑', delete: '删除', deleting: '删除中…', deleteTitle: '删除买入记录', deleteWarning: '删除后该笔买入的份额和资金流也会从人民币基金持仓及多币种收益计算中移除。', confirmDelete: '确认删除',
    navHint: '系统按买入日期使用已保存的精确单位净值计算份额。若该日没有 NAV，请先同步净值或补录该日 NAV。', feeHint: '小数口径，例如 0.0015 = 0.15%。买入金额按实际支付总额计算。', error: '操作失败',
  } : {
    title: 'One-time purchases', add: 'Record purchase', empty: 'No one-time purchases yet', date: 'Purchase date', amount: 'Cash paid', nav: 'Purchase NAV', fee: 'Fee', feeRate: 'Purchase fee rate', shares: 'Shares', notes: 'Notes', actions: 'Actions',
    createTitle: 'Record one-time purchase', editTitle: 'Edit one-time purchase', save: 'Save', cancel: 'Cancel', edit: 'Edit', delete: 'Delete', deleting: 'Deleting…', deleteTitle: 'Delete purchase', deleteWarning: 'Deleting this purchase also removes its shares and external cash flow from CNY fund holdings and multi-currency performance.', confirmDelete: 'Delete purchase',
    navHint: 'Shares are calculated from the exact stored unit NAV on the purchase date. Sync NAV or add that date manually first if it is missing.', feeHint: 'Decimal form, e.g. 0.0015 = 0.15%. The amount is treated as gross cash paid.', error: 'Operation failed',
  }
}

function money(value: string, locale: string) {
  const number = Number(value)
  return Number.isFinite(number) ? new Intl.NumberFormat(locale, { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(number) : '—'
}

function percent(value: string) {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : '—'
}

export function FundPurchasesPanel({ fund, isZh, locale }: { fund: MutualFund; isZh: boolean; locale: string }) {
  const t = copy(isZh)
  const queryClient = useQueryClient()
  const purchases = useQuery({ queryKey: queryKeys.fundPurchases(fund.id), queryFn: () => api.getFundPurchases(fund.id) })
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<FundPurchase | null>(null)
  const [toDelete, setToDelete] = useState<FundPurchase | null>(null)
  const [form, setForm] = useState(emptyForm)

  const savePurchase = useMutation({
    mutationFn: ({ purchaseId, input }: { purchaseId?: string; input: FundPurchaseInput }) => purchaseId
      ? api.updateFundPurchase(fund.id, purchaseId, input)
      : api.createFundPurchase(fund.id, input),
    onSuccess: async () => {
      await invalidateFundQueries(queryClient, fund.id)
      setDialogOpen(false)
      setEditing(null)
    },
  })

  const deletePurchase = useMutation({
    mutationFn: (purchaseId: string) => api.deleteFundPurchase(fund.id, purchaseId),
    onSuccess: async () => {
      await invalidateFundQueries(queryClient, fund.id)
      setToDelete(null)
    },
  })

  const openPurchase = (purchase?: FundPurchase) => {
    setEditing(purchase ?? null)
    setForm(purchase ? {
      purchaseDate: purchase.purchaseDate,
      grossAmount: purchase.grossAmount,
      purchaseFeeRate: purchase.purchaseFeeRate,
      notes: purchase.notes ?? '',
    } : emptyForm())
    savePurchase.reset()
    setDialogOpen(true)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!form.grossAmount.trim()) return
    savePurchase.mutate({
      purchaseId: editing?.id,
      input: {
        purchaseDate: form.purchaseDate,
        grossAmount: form.grossAmount.trim(),
        purchaseFeeRate: form.purchaseFeeRate.trim() || '0',
        notes: form.notes.trim() || null,
      },
    })
  }

  const operationError = savePurchase.error ?? deletePurchase.error
  const rows = purchases.data?.data ?? []

  return <>
    <Panel title={t.title} action={<button type="button" className="button button-primary button-small" onClick={() => openPurchase()}><Plus size={14} />{t.add}</button>}>
      {operationError ? <div className="fund-error" role="alert"><strong>{t.error}</strong><span>{operationError instanceof Error ? operationError.message : String(operationError)}</span></div> : null}
      {purchases.isLoading ? <LoadingBlock lines={3} /> : purchases.isError ? <ErrorState onRetry={() => void purchases.refetch()} /> : rows.length === 0 ? <EmptyState title={t.empty} action={<button type="button" className="button button-secondary button-small" onClick={() => openPurchase()}>{t.add}</button>} /> : <div className="data-table-wrap"><table className="data-table fund-purchase-table"><thead><tr><th>{t.date}</th><th>{t.amount}</th><th>{t.nav}</th><th>{t.fee}</th><th>{t.shares}</th><th>{t.notes}</th><th>{t.actions}</th></tr></thead><tbody>{rows.map((purchase) => <tr key={purchase.id}><td><strong>{purchase.purchaseDate}</strong></td><td>{money(purchase.grossAmount, locale)}</td><td>{Number(purchase.nav).toFixed(4)}</td><td>{money(purchase.purchaseFee, locale)} · {percent(purchase.purchaseFeeRate)}</td><td>{Number(purchase.shares).toFixed(4)}</td><td>{purchase.notes ?? '—'}</td><td><div className="fund-purchase-actions"><button type="button" className="icon-button" title={t.edit} aria-label={t.edit} onClick={() => openPurchase(purchase)}><Edit3 size={14} /></button><button type="button" className="icon-button fund-purchase-delete" title={t.delete} aria-label={t.delete} onClick={() => setToDelete(purchase)}><Trash2 size={14} /></button></div></td></tr>)}</tbody></table></div>}
    </Panel>

    {dialogOpen ? <Dialog labelledBy="fund-purchase-dialog-title" onClose={() => { if (!savePurchase.isPending) setDialogOpen(false) }}><button type="button" className="modal-close icon-button" disabled={savePurchase.isPending} onClick={() => setDialogOpen(false)} aria-label={t.cancel}><X size={17} /></button><h2 id="fund-purchase-dialog-title">{editing ? t.editTitle : t.createTitle}</h2><p className="fund-dialog-context">{fund.code} · {fund.name}</p><form className="fund-form" onSubmit={submit}><label><span>{t.date}</span><input required type="date" max={today()} value={form.purchaseDate} onChange={(event) => setForm({ ...form, purchaseDate: event.target.value })} /><small>{t.navHint}</small></label><label><span>{t.amount}</span><input required inputMode="decimal" value={form.grossAmount} onChange={(event) => setForm({ ...form, grossAmount: event.target.value })} placeholder="10000" /></label><label><span>{t.feeRate}</span><input required inputMode="decimal" value={form.purchaseFeeRate} onChange={(event) => setForm({ ...form, purchaseFeeRate: event.target.value })} /><small>{t.feeHint}</small></label><label><span>{t.notes}</span><input maxLength={500} value={form.notes} onChange={(event) => setForm({ ...form, notes: event.target.value })} /></label><div className="fund-form-actions"><button type="button" className="button button-secondary" disabled={savePurchase.isPending} onClick={() => setDialogOpen(false)}>{t.cancel}</button><button type="submit" className="button button-primary" disabled={savePurchase.isPending}>{t.save}</button></div></form></Dialog> : null}

    {toDelete ? <Dialog labelledBy="fund-purchase-delete-title" onClose={() => { if (!deletePurchase.isPending) setToDelete(null) }}><button type="button" className="modal-close icon-button" disabled={deletePurchase.isPending} onClick={() => setToDelete(null)} aria-label={t.cancel}><X size={17} /></button><h2 id="fund-purchase-delete-title">{t.deleteTitle}</h2><p className="fund-delete-name"><strong>{toDelete.purchaseDate}</strong> · {money(toDelete.grossAmount, locale)}</p><div className="fund-delete-warning"><Trash2 size={17} /><span>{t.deleteWarning}</span></div><div className="fund-form-actions"><button type="button" className="button button-secondary" disabled={deletePurchase.isPending} onClick={() => setToDelete(null)}>{t.cancel}</button><button type="button" className="button fund-delete-confirm" disabled={deletePurchase.isPending} onClick={() => deletePurchase.mutate(toDelete.id)}><Trash2 size={15} />{deletePurchase.isPending ? t.deleting : t.confirmDelete}</button></div></Dialog> : null}
  </>
}
