import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CalendarDays, ChevronDown, ChevronRight, Edit3, Plus, RefreshCw, Search, Trash2, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Dialog } from '../components/Dialog'
import { FundNavChart } from '../components/charts/FundNavChart'
import { Panel } from '../components/Panel'
import { api } from '../lib/api'
import type { FundLookupResult } from '../lib/api/funds'
import { invalidateAutoDcaQueries, invalidateFundQueries, queryKeys } from '../lib/queryKeys'
import type { AutoDcaRule, AutoDcaRuleInput, MutualFund, MutualFundInput } from '../types'

const today = () => new Date().toISOString().slice(0, 10)
const emptyFundForm = () => ({ code: '', name: '', managementFeeRate: '', confirmationTradingDays: '', shareClass: '' })

function copy(isZh: boolean) {
  return isZh ? {
    eyebrow: '人民币资产 · 国内基金', title: '国内基金定投', subtitle: '以规则和真实基金净值重建每日定投，默认按月/年聚合。当前阶段不计入美元总资产。',
    addFund: '添加基金', funds: '基金', noFunds: '还没有国内基金', noFundsHint: '输入 6 位基金代码即可自动获取基金资料并同步历史净值。',
    latestNav: '最新净值', navDate: '净值日期', gaps: '缺失净值', openDays: '开放日', sync: '同步全部 NAV', syncing: '同步中…', edit: '编辑', delete: '删除基金', deleting: '删除中…',
    deleteTitle: '删除国内基金', deleteWarning: '删除后无法恢复。该基金、自动定投规则和已同步的历史净值都会永久删除；共享的中国交易日历不会删除。', deleteConfirm: '确认删除',
    source: '数据源', calendarUnavailable: '尚未同步中国交易日历', calendarHealthy: '最近范围没有发现开放日 NAV 缺口', calendarGap: '开放日缺少 NAV，不会生成对应定投。',
    rules: '自动定投规则', addRule: '添加规则', noRules: '这个基金还没有自动定投规则', amountPerDay: '每开放日投入', start: '开始', end: '结束', fee: '申购费', enabled: '启用', disabled: '停用',
    monthly: '按月', yearly: '按年', projection: '定投汇总', period: '期间', executions: '次数', confirmed: '已确认', invested: '投入', fees: '申购费', shares: '份额', avgCost: '平均成本', value: '当前价值', pnl: '盈亏', returnRate: '收益率',
    daily: '日级派生明细', orderDate: '定投日', confirmDate: '确认日', nav: 'NAV', net: '净申购', pending: '待确认', confirmedStatus: '已确认',
    tradingCalendar: '确认日口径', persistedCalendar: '中国开放日历；未覆盖历史用 NAV 日期回退', observedCalendar: '仅 NAV 日期（尚未同步交易日历）',
    fundDialogCreate: '添加国内基金', fundDialogEdit: '编辑基金', code: '基金代码', name: '基金名称', managementFee: '管理费率', managementFeeHint: '小数口径，例如 0.006 = 0.6%。仅作元数据展示，不从 NAV 二次扣除。', confirmationDays: '份额确认 T+n', shareClass: '份额类别', save: '保存', cancel: '取消',
    lookup: '查询', lookingUp: '查询中…', lookupHint: '输入 6 位基金代码，从东方财富自动获取名称、类型、成立日、管理费率、确认日和最新净值。', lookupOk: '已自动获取基金资料', lookupFallback: '自动查询失败，可手动补全资料后继续保存。', autoField: '数据源已自动填充；如无法获取才需要手工输入。',
    fundType: '基金类型', inceptionDate: '成立日期', historyAvailability: '历史净值', historyAvailable: '可获取', historyUnavailable: '暂未获取',
    navHistory: '历史净值曲线', navHistoryHint: '曲线来自已同步的单位净值；同步时会尽量从基金成立日开始补齐。', noNavHistory: '当前还没有足够的历史净值数据。',
    ruleDialogCreate: '添加自动定投规则', ruleDialogEdit: '编辑自动定投规则', purchaseFee: '申购费率', purchaseFeeHint: '小数口径，例如 0.0015 = 0.15%。定投金额按实际支付总额计算。', noEnd: '留空表示持续', historyRewrite: '修改规则会重算整个历史派生结果，不会修改真实交易流水。',
    manualNav: '补录 NAV', manualNavDate: 'NAV 日期', manualNavValue: '单位净值', addNav: '写入', selectedFund: '当前基金', phaseBoundary: 'CNY 基金目前独立展示；FX、多币种现金账本和总资产/TWR/XIRR 合并在后续阶段处理。',
    error: '操作失败', providerHint: '自动同步使用独立基金数据源；上游失败不会删除已有 NAV。', expand: '展开日级', collapse: '收起日级',
  } : {
    eyebrow: 'CNY assets · China funds', title: 'China fund DCA', subtitle: 'Rebuild daily DCA from rules and observed fund NAV, summarized by month or year. CNY assets are not yet merged into the USD portfolio.',
    addFund: 'Add fund', funds: 'Funds', noFunds: 'No China funds yet', noFundsHint: 'Enter a six-digit fund code to fetch metadata and historical NAV automatically.',
    latestNav: 'Latest NAV', navDate: 'NAV date', gaps: 'Missing NAV', openDays: 'Open days', sync: 'Sync all NAV', syncing: 'Syncing…', edit: 'Edit', delete: 'Delete fund', deleting: 'Deleting…',
    deleteTitle: 'Delete China fund', deleteWarning: 'This cannot be undone. The fund, its auto-DCA rules, and all synced historical NAV will be permanently deleted. The shared China trading calendar is kept.', deleteConfirm: 'Delete fund',
    source: 'Source', calendarUnavailable: 'China trading calendar has not been synced yet', calendarHealthy: 'No open-day NAV gaps found in the current range', calendarGap: 'Open day has no NAV, so no DCA execution is created.',
    rules: 'Auto-DCA rules', addRule: 'Add rule', noRules: 'No auto-DCA rule for this fund', amountPerDay: 'Per open day', start: 'Start', end: 'End', fee: 'Purchase fee', enabled: 'Enabled', disabled: 'Disabled',
    monthly: 'Monthly', yearly: 'Yearly', projection: 'DCA summary', period: 'Period', executions: 'Runs', confirmed: 'Confirmed', invested: 'Invested', fees: 'Fees', shares: 'Shares', avgCost: 'Avg cost', value: 'Current value', pnl: 'P/L', returnRate: 'Return',
    daily: 'Derived daily detail', orderDate: 'DCA date', confirmDate: 'Confirm date', nav: 'NAV', net: 'Net subscription', pending: 'Pending', confirmedStatus: 'Confirmed',
    tradingCalendar: 'Confirmation calendar', persistedCalendar: 'Persisted China open days; NAV-date fallback for uncovered history', observedCalendar: 'NAV dates only (calendar not synced yet)',
    fundDialogCreate: 'Add China fund', fundDialogEdit: 'Edit fund', code: 'Fund code', name: 'Fund name', managementFee: 'Management fee rate', managementFeeHint: 'Decimal form, e.g. 0.006 = 0.6%. Metadata only; it is not deducted from NAV again.', confirmationDays: 'Share confirmation T+n', shareClass: 'Share class', save: 'Save', cancel: 'Cancel',
    lookup: 'Lookup', lookingUp: 'Looking up…', lookupHint: 'Enter a six-digit code to fetch name, type, inception date, management fee, confirmation timing and latest NAV from EastMoney.', lookupOk: 'Fund metadata loaded automatically', lookupFallback: 'Automatic lookup failed. Fill in the missing fields manually to continue.', autoField: 'Provider data is prefilled automatically; manual input is only needed when unavailable.',
    fundType: 'Fund type', inceptionDate: 'Inception', historyAvailability: 'Historical NAV', historyAvailable: 'Available', historyUnavailable: 'Unavailable',
    navHistory: 'Historical NAV', navHistoryHint: 'The chart uses stored unit NAV. Sync attempts to backfill from the fund inception date.', noNavHistory: 'There is not enough historical NAV data yet.',
    ruleDialogCreate: 'Add auto-DCA rule', ruleDialogEdit: 'Edit auto-DCA rule', purchaseFee: 'Purchase fee rate', purchaseFeeHint: 'Decimal form, e.g. 0.0015 = 0.15%. DCA amount is treated as gross cash paid.', noEnd: 'Leave blank to continue', historyRewrite: 'Editing a rule recomputes its full derived history and never rewrites the real transaction ledger.',
    manualNav: 'Add NAV', manualNavDate: 'NAV date', manualNavValue: 'Unit NAV', addNav: 'Write', selectedFund: 'Selected fund', phaseBoundary: 'CNY funds stay separate in this phase. FX, multi-currency cash ledger and portfolio/TWR/XIRR integration come later.',
    error: 'Operation failed', providerHint: 'Automatic sync uses a fund-specific provider. Upstream failure does not erase stored NAV.', expand: 'Show daily', collapse: 'Hide daily',
  }
}

function cny(value: string | null | undefined, locale: string) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return new Intl.NumberFormat(locale, { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(number)
}

function percent(value: string | null | undefined) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : '—'
}

function navValue(value: string | null | undefined) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(4) : '—'
}

function newestNav<T extends { navDate: string; retrievedAt: string }>(rows: T[]): T | undefined {
  return rows.reduce<T | undefined>((best, row) => {
    if (!best || row.navDate > best.navDate || (row.navDate === best.navDate && row.retrievedAt > best.retrievedAt)) return row
    return best
  }, undefined)
}

export function FundsPage() {
  const { i18n } = useTranslation()
  const queryClient = useQueryClient()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const t = copy(isZh)
  const locale = isZh ? 'zh-CN' : 'en-US'

  const funds = useQuery({ queryKey: queryKeys.funds, queryFn: api.getFunds })
  const rules = useQuery({ queryKey: queryKeys.autoDcaRules, queryFn: api.getAutoDcaRules })
  const [selectedFundId, setSelectedFundId] = useState<string | null>(null)
  const [selectedRuleId, setSelectedRuleId] = useState<string | null>(null)
  const [groupBy, setGroupBy] = useState<'MONTH' | 'YEAR'>('MONTH')
  const [expandedPeriod, setExpandedPeriod] = useState<string | null>(null)
  const [fundDialogOpen, setFundDialogOpen] = useState(false)
  const [editingFund, setEditingFund] = useState<MutualFund | null>(null)
  const [fundForm, setFundForm] = useState(emptyFundForm)
  const [fundLookup, setFundLookup] = useState<FundLookupResult | null>(null)
  const [fundToDelete, setFundToDelete] = useState<MutualFund | null>(null)
  const [ruleDialogOpen, setRuleDialogOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<AutoDcaRule | null>(null)
  const [ruleForm, setRuleForm] = useState({ amount: '100', startDate: today(), endDate: '', purchaseFeeRate: '0', enabled: true })
  const [manualNavOpen, setManualNavOpen] = useState(false)
  const [manualNavForm, setManualNavForm] = useState({ navDate: today(), nav: '' })

  useEffect(() => {
    const rows = funds.data?.data ?? []
    if (rows.length === 0) {
      if (selectedFundId) setSelectedFundId(null)
      return
    }
    if (!selectedFundId || !rows.some((fund) => fund.id === selectedFundId)) setSelectedFundId(rows[0].id)
  }, [funds.data?.data, selectedFundId])

  const selectedFund = useMemo(() => (funds.data?.data ?? []).find((fund) => fund.id === selectedFundId) ?? null, [funds.data?.data, selectedFundId])
  const fundRules = useMemo(() => (rules.data?.data ?? []).filter((rule) => rule.instrumentId === selectedFundId), [rules.data?.data, selectedFundId])

  useEffect(() => {
    if (fundRules.length === 0) {
      if (selectedRuleId) setSelectedRuleId(null)
      return
    }
    if (!selectedRuleId || !fundRules.some((rule) => rule.id === selectedRuleId)) setSelectedRuleId(fundRules[0].id)
  }, [fundRules, selectedRuleId])

  useEffect(() => setExpandedPeriod(null), [selectedRuleId, groupBy])

  const nav = useQuery({ queryKey: selectedFundId ? queryKeys.fundNav(selectedFundId) : ['fund-nav-none'], queryFn: () => api.getFundNav(selectedFundId!), enabled: Boolean(selectedFundId) })
  const calendar = useQuery({ queryKey: selectedFundId ? queryKeys.fundCalendar(selectedFundId) : ['fund-calendar-none'], queryFn: () => api.getFundCalendar(selectedFundId!), enabled: Boolean(selectedFundId) })
  const projection = useQuery({ queryKey: selectedRuleId ? queryKeys.autoDcaProjection(selectedRuleId, groupBy, false) : ['auto-dca-none'], queryFn: () => api.getAutoDcaProjection(selectedRuleId!, groupBy, false), enabled: Boolean(selectedRuleId) })
  const dailyProjection = useQuery({ queryKey: selectedRuleId ? queryKeys.autoDcaProjection(selectedRuleId, groupBy, true) : ['auto-dca-daily-none'], queryFn: () => api.getAutoDcaProjection(selectedRuleId!, groupBy, true), enabled: Boolean(selectedRuleId && expandedPeriod) })
  const latestNav = newestNav(nav.data?.data ?? [])

  const lookupFund = useMutation({
    mutationFn: (code: string) => api.lookupFund(code),
    onSuccess: (result) => {
      const lookup = result.data
      setFundLookup(lookup)
      setFundForm((current) => ({
        ...current,
        code: lookup.code,
        name: lookup.name,
        managementFeeRate: lookup.managementFeeRate ?? '',
        confirmationTradingDays: lookup.confirmationTradingDays == null ? '' : String(lookup.confirmationTradingDays),
        shareClass: lookup.shareClass ?? '',
      }))
    },
  })

  const syncFund = useMutation({
    mutationFn: async ({ id, code }: { id: string; code: string }) => {
      let startDate: string | undefined
      try {
        const lookup = await api.lookupFund(code)
        startDate = lookup.data.inceptionDate ?? undefined
      } catch {
        startDate = undefined
      }
      return api.syncFund(id, startDate)
    },
    onSuccess: async (_result, variables) => {
      await invalidateFundQueries(queryClient, variables.id)
      await queryClient.invalidateQueries({ queryKey: ['auto-dca-projection'] })
    },
  })

  const saveFund = useMutation({
    mutationFn: ({ id, input }: { id?: string; input: MutualFundInput }) => id ? api.updateFund(id, input) : api.createFund(input),
    onSuccess: async (result, variables) => {
      await invalidateFundQueries(queryClient, result.data.id)
      setSelectedFundId(result.data.id)
      setFundDialogOpen(false)
      if (!variables.id) syncFund.mutate({ id: result.data.id, code: result.data.code })
    },
  })

  const deleteFund = useMutation({
    mutationFn: (id: string) => api.deleteFund(id),
    onSuccess: async (_result, id) => {
      setFundToDelete(null)
      setSelectedRuleId(null)
      setExpandedPeriod(null)
      await invalidateFundQueries(queryClient, id)
      await queryClient.invalidateQueries({ queryKey: ['auto-dca-projection'] })
    },
  })

  const putNav = useMutation({
    mutationFn: ({ id, navDate, value }: { id: string; navDate: string; value: string }) => api.putFundNav(id, navDate, value),
    onSuccess: async (_result, variables) => {
      await invalidateFundQueries(queryClient, variables.id)
      await queryClient.invalidateQueries({ queryKey: ['auto-dca-projection'] })
      setManualNavOpen(false)
    },
  })

  const saveRule = useMutation({
    mutationFn: ({ id, input }: { id?: string; input: AutoDcaRuleInput }) => id ? api.updateAutoDcaRule(id, input) : api.createAutoDcaRule(input),
    onSuccess: async (result) => {
      await invalidateAutoDcaQueries(queryClient)
      await queryClient.invalidateQueries({ queryKey: ['auto-dca-projection'] })
      setSelectedRuleId(result.data.id)
      setRuleDialogOpen(false)
    },
  })

  const openFund = (fund?: MutualFund) => {
    setEditingFund(fund ?? null)
    setFundLookup(null)
    lookupFund.reset()
    setFundForm(fund ? {
      code: fund.code,
      name: fund.name,
      managementFeeRate: fund.managementFeeRate,
      confirmationTradingDays: String(fund.confirmationTradingDays),
      shareClass: fund.shareClass ?? '',
    } : emptyFundForm())
    setFundDialogOpen(true)
  }

  const changeFundCode = (value: string) => {
    const code = value.replace(/\D/g, '').slice(0, 6)
    setFundForm((current) => ({ ...current, code }))
    if (fundLookup?.code !== code) setFundLookup(null)
    lookupFund.reset()
  }

  const runFundLookup = () => {
    if (/^\d{6}$/.test(fundForm.code)) lookupFund.mutate(fundForm.code)
  }

  const submitFund = (event: FormEvent) => {
    event.preventDefault()
    const confirmationTradingDays = Number(fundForm.confirmationTradingDays)
    if (!Number.isInteger(confirmationTradingDays)) return
    saveFund.mutate({
      id: editingFund?.id,
      input: {
        code: fundForm.code.trim(),
        name: fundForm.name.trim(),
        managementFeeRate: fundForm.managementFeeRate.trim() || '0',
        confirmationTradingDays,
        shareClass: fundForm.shareClass.trim() || null,
      },
    })
  }

  const openRule = (rule?: AutoDcaRule) => {
    if (!selectedFund) return
    setEditingRule(rule ?? null)
    setRuleForm(rule ? {
      amount: rule.amount,
      startDate: rule.startDate,
      endDate: rule.endDate ?? '',
      purchaseFeeRate: rule.purchaseFeeRate,
      enabled: rule.enabled,
    } : { amount: '100', startDate: today(), endDate: '', purchaseFeeRate: '0', enabled: true })
    setRuleDialogOpen(true)
  }

  const submitRule = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedFund) return
    saveRule.mutate({
      id: editingRule?.id,
      input: {
        fundCode: selectedFund.code,
        amount: ruleForm.amount.trim(),
        startDate: ruleForm.startDate,
        endDate: ruleForm.endDate || null,
        purchaseFeeRate: ruleForm.purchaseFeeRate.trim() || '0',
        enabled: ruleForm.enabled,
      },
    })
  }

  const submitManualNav = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedFundId || !manualNavForm.nav.trim()) return
    putNav.mutate({ id: selectedFundId, navDate: manualNavForm.navDate, value: manualNavForm.nav.trim() })
  }

  const dailyRows = (dailyProjection.data?.data.daily ?? []).filter((row) => {
    if (!expandedPeriod) return false
    return groupBy === 'YEAR' ? row.navDate.slice(0, 4) === expandedPeriod : row.navDate.slice(0, 7) === expandedPeriod
  })
  const operationError = saveFund.error ?? deleteFund.error ?? syncFund.error ?? putNav.error ?? saveRule.error
  const creatingFund = !editingFund
  const metadataVisible = Boolean(editingFund || fundLookup || lookupFund.isError)
  const lookupManagementResolved = Boolean(fundLookup?.managementFeeRate)
  const lookupConfirmationResolved = fundLookup?.confirmationTradingDays != null
  const lookupShareClassResolved = Boolean(fundLookup?.shareClass)

  return <div className="page funds-page">
    <div className="page-intro">
      <div><span className="page-eyebrow">{t.eyebrow}</span><h1>{t.title}</h1><p>{t.subtitle}</p></div>
      <button type="button" className="button button-primary" onClick={() => openFund()}><Plus size={16} />{t.addFund}</button>
    </div>
    <div className="fund-boundary-note"><AlertTriangle size={16} /><span>{t.phaseBoundary}</span></div>
    {operationError ? <div className="fund-error" role="alert"><strong>{t.error}</strong><span>{operationError instanceof Error ? operationError.message : String(operationError)}</span></div> : null}

    {funds.isLoading ? <Panel><LoadingBlock lines={6} /></Panel> : funds.isError ? <ErrorState onRetry={() => void funds.refetch()} /> : (funds.data?.data.length ?? 0) === 0 ? <Panel><EmptyState title={t.noFunds} action={<button type="button" className="button button-primary" onClick={() => openFund()}><Plus size={15} />{t.addFund}</button>} /><p className="fund-empty-hint">{t.noFundsHint}</p></Panel> : <div className="fund-workspace">
      <Panel title={t.funds} className="fund-list-panel">
        <div className="fund-list">{(funds.data?.data ?? []).map((fund) => <button key={fund.id} type="button" className={`fund-list-item ${fund.id === selectedFundId ? 'fund-list-item-active' : ''}`} onClick={() => setSelectedFundId(fund.id)}><span><strong>{fund.code}</strong><small>{fund.name}</small></span><ChevronRight size={15} /></button>)}</div>
      </Panel>

      <div className="fund-detail-stack">{selectedFund ? <>
        <Panel title={`${selectedFund.code} · ${selectedFund.name}`} detail={`${t.selectedFund} · CNY`} action={<div className="fund-panel-actions"><button type="button" className="button button-secondary button-small" onClick={() => openFund(selectedFund)}><Edit3 size={14} />{t.edit}</button><button type="button" className="button button-secondary button-small" disabled={syncFund.isPending || deleteFund.isPending} onClick={() => syncFund.mutate({ id: selectedFund.id, code: selectedFund.code })}><RefreshCw size={14} className={syncFund.isPending ? 'spin' : ''} />{syncFund.isPending ? t.syncing : t.sync}</button><button type="button" className="button button-secondary button-small fund-delete-trigger" disabled={deleteFund.isPending} onClick={() => setFundToDelete(selectedFund)}><Trash2 size={14} />{t.delete}</button></div>}>
          <DataStateBanner status={nav.data?.meta.status ?? (nav.isError ? 'UNAVAILABLE' : 'STALE')} source={latestNav?.source ?? nav.data?.meta.source} retrievedAt={latestNav?.retrievedAt ?? nav.data?.meta.retrievedAt} />
          <div className="fund-stat-grid">
            <div className="fund-stat"><span>{t.latestNav}</span><strong>{navValue(latestNav?.nav)}</strong><small>{latestNav?.source ?? '—'}</small></div>
            <div className="fund-stat"><span>{t.navDate}</span><strong>{latestNav?.navDate ?? '—'}</strong><small>T+{selectedFund.confirmationTradingDays}</small></div>
            <div className="fund-stat"><span>{t.openDays}</span><strong>{calendar.data?.data.expectedTradingDays ?? '—'}</strong><small>{calendar.data?.data.source ?? '—'}</small></div>
            <div className={`fund-stat ${(calendar.data?.data.missingNavDates.length ?? 0) > 0 ? 'fund-stat-warning' : ''}`}><span>{t.gaps}</span><strong>{calendar.data?.data.calendarAvailable ? calendar.data.data.missingNavDates.length : '—'}</strong><small>{calendar.data?.data.calendarAvailable ? (calendar.data.data.missingNavDates.length ? t.calendarGap : t.calendarHealthy) : t.calendarUnavailable}</small></div>
          </div>
          {(calendar.data?.data.missingNavDates.length ?? 0) > 0 ? <div className="fund-gap-dates"><AlertTriangle size={14} /><span>{calendar.data!.data.missingNavDates.slice(0, 8).join(' · ')}{calendar.data!.data.missingNavDates.length > 8 ? ' …' : ''}</span></div> : null}
          <div className="fund-meta-row"><span>{t.managementFee}: <strong>{percent(selectedFund.managementFeeRate)}</strong></span><span>{t.shareClass}: <strong>{selectedFund.shareClass ?? '—'}</strong></span><span>{t.source}: <strong>{latestNav?.source ?? 'MANUAL'}</strong></span><button type="button" className="text-button" onClick={() => setManualNavOpen(true)}>{t.manualNav}</button></div>
          <p className="fund-provider-hint">{t.providerHint}</p>
        </Panel>

        <Panel title={t.navHistory} detail={t.navHistoryHint}>
          {nav.isLoading ? <LoadingBlock lines={5} /> : nav.isError ? <ErrorState onRetry={() => void nav.refetch()} /> : (nav.data?.data.length ?? 0) > 1 ? <FundNavChart data={nav.data?.data ?? []} /> : <p className="fund-nav-empty">{t.noNavHistory}</p>}
        </Panel>

        <Panel title={t.rules} action={<button type="button" className="button button-primary button-small" onClick={() => openRule()}><Plus size={14} />{t.addRule}</button>}>
          {rules.isLoading ? <LoadingBlock lines={3} /> : rules.isError ? <ErrorState onRetry={() => void rules.refetch()} /> : fundRules.length === 0 ? <EmptyState title={t.noRules} action={<button type="button" className="button button-secondary button-small" onClick={() => openRule()}>{t.addRule}</button>} /> : <div className="fund-rule-list">{fundRules.map((rule) => <button key={rule.id} type="button" className={`fund-rule-card ${rule.id === selectedRuleId ? 'fund-rule-card-active' : ''}`} onClick={() => setSelectedRuleId(rule.id)}><span className="fund-rule-main"><strong>{cny(rule.amount, locale)}</strong><small>{t.amountPerDay} · {rule.startDate}{rule.endDate ? ` → ${rule.endDate}` : ''}</small></span><span className="fund-rule-side"><span className={rule.enabled ? 'status-chip status-chip-ok' : 'status-chip'}>{rule.enabled ? t.enabled : t.disabled}</span><span>{percent(rule.purchaseFeeRate)}</span><span className="fund-rule-edit" role="button" tabIndex={0} onClick={(event) => { event.stopPropagation(); openRule(rule) }} onKeyDown={(event) => { if (event.key === 'Enter') { event.stopPropagation(); openRule(rule) } }}><Edit3 size={14} /></span></span></button>)}</div>}
        </Panel>

        {selectedRuleId ? <Panel title={t.projection} action={<div className="fund-segmented"><button type="button" className={groupBy === 'MONTH' ? 'active' : ''} onClick={() => setGroupBy('MONTH')}>{t.monthly}</button><button type="button" className={groupBy === 'YEAR' ? 'active' : ''} onClick={() => setGroupBy('YEAR')}>{t.yearly}</button></div>} flush>
          {projection.isLoading ? <div className="fund-panel-loading"><LoadingBlock lines={5} /></div> : projection.isError ? <div className="fund-panel-loading"><ErrorState onRetry={() => void projection.refetch()} /></div> : projection.data ? <>
            <div className="fund-projection-meta"><CalendarDays size={15} /><span>{t.tradingCalendar}: {projection.data.data.tradingDaySource === 'OBSERVED_FUND_NAV_DATES' ? t.observedCalendar : t.persistedCalendar}</span></div>
            <div className="data-table-wrap"><table className="data-table fund-summary-table"><thead><tr><th>{t.period}</th><th>{t.executions}</th><th>{t.confirmed}</th><th>{t.invested}</th><th>{t.fees}</th><th>{t.shares}</th><th>{t.avgCost}</th><th>{t.value}</th><th>{t.pnl}</th><th>{t.returnRate}</th><th /></tr></thead><tbody>{projection.data.data.summaries.map((summary) => <tr key={summary.period}><td><strong>{summary.period}</strong></td><td>{summary.executionCount}</td><td>{summary.confirmedCount}</td><td>{cny(summary.grossAmount, locale)}</td><td>{cny(summary.purchaseFees, locale)}</td><td>{Number(summary.shares).toFixed(4)}</td><td>{summary.averageCostPerShare ? navValue(summary.averageCostPerShare) : '—'}</td><td>{cny(summary.currentValue, locale)}</td><td>{cny(summary.currentPnl, locale)}</td><td>{percent(summary.returnRate)}</td><td><button type="button" className="icon-button" title={expandedPeriod === summary.period ? t.collapse : t.expand} aria-label={expandedPeriod === summary.period ? t.collapse : t.expand} onClick={() => setExpandedPeriod((current) => current === summary.period ? null : summary.period)}>{expandedPeriod === summary.period ? <ChevronDown size={15} /> : <ChevronRight size={15} />}</button></td></tr>)}</tbody></table></div>
            {expandedPeriod ? <div className="fund-daily-section"><div className="fund-daily-heading"><strong>{t.daily} · {expandedPeriod}</strong><small>{t.historyRewrite}</small></div>{dailyProjection.isLoading ? <LoadingBlock lines={4} /> : dailyProjection.isError ? <ErrorState onRetry={() => void dailyProjection.refetch()} /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>{t.orderDate}</th><th>{t.confirmDate}</th><th>{t.nav}</th><th>{t.invested}</th><th>{t.fees}</th><th>{t.net}</th><th>{t.shares}</th><th>{t.confirmed}</th></tr></thead><tbody>{dailyRows.map((row) => <tr key={`${row.navDate}-${row.nav}`}><td>{row.navDate}</td><td>{row.confirmationDate ?? '—'}</td><td>{navValue(row.nav)}</td><td>{cny(row.grossAmount, locale)}</td><td>{cny(row.purchaseFee, locale)}</td><td>{cny(row.netSubscribedAmount, locale)}</td><td>{Number(row.shares).toFixed(4)}</td><td><span className={row.status === 'CONFIRMED' ? 'status-chip status-chip-ok' : 'status-chip'}>{row.status === 'CONFIRMED' ? t.confirmedStatus : t.pending}</span></td></tr>)}</tbody></table></div>}</div> : null}
          </> : null}
        </Panel> : null}
      </> : null}</div>
    </div>}

    {fundDialogOpen ? <Dialog labelledBy="fund-dialog-title" onClose={() => setFundDialogOpen(false)}><button type="button" className="modal-close icon-button" onClick={() => setFundDialogOpen(false)} aria-label={t.cancel}><X size={17} /></button><h2 id="fund-dialog-title">{editingFund ? t.fundDialogEdit : t.fundDialogCreate}</h2><form className="fund-form" onSubmit={submitFund}>
      {creatingFund ? <>
        <div className="fund-code-lookup"><label><span>{t.code}</span><input required inputMode="numeric" maxLength={6} value={fundForm.code} onChange={(event) => changeFundCode(event.target.value)} placeholder="000001" /></label><button type="button" className="button button-secondary" disabled={!/^\d{6}$/.test(fundForm.code) || lookupFund.isPending} onClick={runFundLookup}><Search size={14} />{lookupFund.isPending ? t.lookingUp : t.lookup}</button></div>
        <small>{t.lookupHint}</small>
        {fundLookup ? <><div className="fund-lookup-status"><strong>{t.lookupOk}</strong><small>{t.autoField}</small></div><div className="fund-lookup-preview"><div><span>{t.name}</span><strong>{fundLookup.name}</strong></div><div><span>{t.fundType}</span><strong>{fundLookup.fundType ?? '—'}</strong></div><div><span>{t.inceptionDate}</span><strong>{fundLookup.inceptionDate ?? '—'}</strong></div><div><span>{t.latestNav}</span><strong>{fundLookup.latestNav ? `${navValue(fundLookup.latestNav)} · ${fundLookup.latestNavDate ?? ''}` : '—'}</strong></div><div><span>{t.historyAvailability}</span><strong>{fundLookup.historicalNavAvailable ? t.historyAvailable : t.historyUnavailable}</strong></div><div><span>{t.source}</span><strong>{fundLookup.source}</strong></div></div></> : lookupFund.isError ? <div className="fund-lookup-status fund-lookup-status-error"><strong>{t.lookupFallback}</strong><small>{lookupFund.error instanceof Error ? lookupFund.error.message : String(lookupFund.error)}</small></div> : null}
      </> : <label><span>{t.code}</span><input required maxLength={16} value={fundForm.code} onChange={(event) => setFundForm({ ...fundForm, code: event.target.value })} /></label>}

      {metadataVisible ? <>
        <label><span>{t.name}</span><input required maxLength={255} readOnly={creatingFund && Boolean(fundLookup)} value={fundForm.name} onChange={(event) => setFundForm({ ...fundForm, name: event.target.value })} /></label>
        <label><span>{t.managementFee}</span><input required inputMode="decimal" readOnly={creatingFund && lookupManagementResolved} value={fundForm.managementFeeRate} onChange={(event) => setFundForm({ ...fundForm, managementFeeRate: event.target.value })} /><small>{t.managementFeeHint}</small></label>
        <label><span>{t.confirmationDays}</span><input required type="number" min="0" max="10" step="1" readOnly={creatingFund && lookupConfirmationResolved} value={fundForm.confirmationTradingDays} onChange={(event) => setFundForm({ ...fundForm, confirmationTradingDays: event.target.value })} /></label>
        <label><span>{t.shareClass}</span><input maxLength={16} readOnly={creatingFund && lookupShareClassResolved} value={fundForm.shareClass} onChange={(event) => setFundForm({ ...fundForm, shareClass: event.target.value })} placeholder="A / C" /></label>
      </> : null}
      <div className="fund-form-actions"><button type="button" className="button button-secondary" onClick={() => setFundDialogOpen(false)}>{t.cancel}</button><button type="submit" className="button button-primary" disabled={saveFund.isPending || !metadataVisible}>{t.save}</button></div>
    </form></Dialog> : null}

    {fundToDelete ? <Dialog labelledBy="fund-delete-dialog-title" onClose={() => { if (!deleteFund.isPending) setFundToDelete(null) }}><button type="button" className="modal-close icon-button" disabled={deleteFund.isPending} onClick={() => setFundToDelete(null)} aria-label={t.cancel}><X size={17} /></button><h2 id="fund-delete-dialog-title">{t.deleteTitle}</h2><p className="fund-delete-name"><strong>{fundToDelete.code}</strong> · {fundToDelete.name}</p><div className="fund-delete-warning"><AlertTriangle size={17} /><span>{t.deleteWarning}</span></div><div className="fund-form-actions"><button type="button" className="button button-secondary" disabled={deleteFund.isPending} onClick={() => setFundToDelete(null)}>{t.cancel}</button><button type="button" className="button fund-delete-confirm" disabled={deleteFund.isPending} onClick={() => deleteFund.mutate(fundToDelete.id)}><Trash2 size={15} />{deleteFund.isPending ? t.deleting : t.deleteConfirm}</button></div></Dialog> : null}

    {ruleDialogOpen && selectedFund ? <Dialog labelledBy="rule-dialog-title" onClose={() => setRuleDialogOpen(false)}><button type="button" className="modal-close icon-button" onClick={() => setRuleDialogOpen(false)} aria-label={t.cancel}><X size={17} /></button><h2 id="rule-dialog-title">{editingRule ? t.ruleDialogEdit : t.ruleDialogCreate}</h2><p className="fund-dialog-context">{selectedFund.code} · {selectedFund.name}</p><form className="fund-form" onSubmit={submitRule}><label><span>{t.amountPerDay}</span><input required inputMode="decimal" value={ruleForm.amount} onChange={(event) => setRuleForm({ ...ruleForm, amount: event.target.value })} /></label><div className="fund-form-row"><label><span>{t.start}</span><input required type="date" value={ruleForm.startDate} onChange={(event) => setRuleForm({ ...ruleForm, startDate: event.target.value })} /></label><label><span>{t.end}</span><input type="date" value={ruleForm.endDate} onChange={(event) => setRuleForm({ ...ruleForm, endDate: event.target.value })} /><small>{t.noEnd}</small></label></div><label><span>{t.purchaseFee}</span><input required inputMode="decimal" value={ruleForm.purchaseFeeRate} onChange={(event) => setRuleForm({ ...ruleForm, purchaseFeeRate: event.target.value })} /><small>{t.purchaseFeeHint}</small></label><label className="fund-checkbox"><input type="checkbox" checked={ruleForm.enabled} onChange={(event) => setRuleForm({ ...ruleForm, enabled: event.target.checked })} /><span>{t.enabled}</span></label><div className="fund-rewrite-note">{t.historyRewrite}</div><div className="fund-form-actions"><button type="button" className="button button-secondary" onClick={() => setRuleDialogOpen(false)}>{t.cancel}</button><button type="submit" className="button button-primary" disabled={saveRule.isPending}>{t.save}</button></div></form></Dialog> : null}

    {manualNavOpen && selectedFund ? <Dialog labelledBy="nav-dialog-title" onClose={() => setManualNavOpen(false)}><button type="button" className="modal-close icon-button" onClick={() => setManualNavOpen(false)} aria-label={t.cancel}><X size={17} /></button><h2 id="nav-dialog-title">{t.manualNav}</h2><p className="fund-dialog-context">{selectedFund.code} · {selectedFund.name}</p><form className="fund-form" onSubmit={submitManualNav}><label><span>{t.manualNavDate}</span><input required type="date" value={manualNavForm.navDate} onChange={(event) => setManualNavForm({ ...manualNavForm, navDate: event.target.value })} /></label><label><span>{t.manualNavValue}</span><input required inputMode="decimal" value={manualNavForm.nav} onChange={(event) => setManualNavForm({ ...manualNavForm, nav: event.target.value })} /></label><div className="fund-form-actions"><button type="button" className="button button-secondary" onClick={() => setManualNavOpen(false)}>{t.cancel}</button><button type="submit" className="button button-primary" disabled={putNav.isPending}>{t.addNav}</button></div></form></Dialog> : null}
  </div>
}
