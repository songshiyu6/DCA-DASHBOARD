import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Layers3, WalletCards } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import { StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { decimal, formatDate, formatMoney, formatPeriod, formatSignedMoney, formatSignedPercent } from '../lib/format'
import { queryKeys } from '../lib/queryKeys'
import type { ContributionBucket } from '../types'

function trendClass(value: string | null | undefined): string {
  if (!value || decimal(value).isZero()) return 'trend-flat'
  return decimal(value).gt(0) ? 'trend-positive' : 'trend-negative'
}

function SummaryPanel({ title, bucket, isZh, detail }: { title: string; bucket: ContributionBucket; isZh: boolean; detail?: string }) {
  const valueText = bucket.value === null ? '—' : `${formatMoney(bucket.principal)} → ${formatMoney(bucket.value)}`
  return <Panel title={title} detail={detail} className="contribution-summary-panel">
    <strong className="contribution-summary-value">{valueText}</strong>
    <div className="contribution-summary-meta">
      <span><small>{isZh ? '收益' : 'P/L'}</small><strong className={trendClass(bucket.pnl)}>{formatSignedMoney(bucket.pnl)}</strong></span>
      <span><small>{isZh ? '收益率' : 'Return'}</small><strong className={trendClass(bucket.returnRate)}>{formatSignedPercent(bucket.returnRate)}</strong></span>
      <span><small>{isZh ? '平均在场' : 'Avg. market age'}</small><strong>{bucket.averageMarketDays} {isZh ? '天' : 'days'}</strong></span>
    </div>
    {bucket.dataStatus !== 'FRESH' ? <div className="contribution-summary-status"><StatusBadge status={bucket.dataStatus} compact /></div> : null}
  </Panel>
}

export function ContributionsPage() {
  const { i18n } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const plans = useQuery({ queryKey: queryKeys.plans, queryFn: api.getPlans })
  const plan = plans.data?.data.find((candidate) => candidate.status === 'ACTIVE')
  const analysis = useQuery({
    queryKey: plan ? queryKeys.contributionAnalysis(plan.id) : queryKeys.contributionAnalysis('none'),
    queryFn: () => api.getContributionAnalysis(plan?.id ?? ''),
    enabled: Boolean(plan),
  })
  const classify = useMutation({
    mutationFn: (transactionId: string) => api.classifyInitialContribution(plan?.id ?? '', transactionId),
    onSuccess: (result) => {
      if (!plan) return
      queryClient.setQueryData(queryKeys.contributionAnalysis(plan.id), result)
    },
  })

  if (plans.isLoading) return <div className="page"><LoadingBlock lines={8} /></div>
  if (plans.isError) return <div className="page"><ErrorState onRetry={() => void plans.refetch()} /></div>
  if (!plan) return <div className="page contributions-page"><div className="page-intro"><div><span className="page-eyebrow">{isZh ? '资金批次' : 'Capital batches'}</span><h1>{isZh ? '投入分析' : 'Contributions'}</h1></div></div><Panel><EmptyState title={isZh ? '先创建定投计划' : 'Create a plan first'} detail={isZh ? '初始资金和每月定投都归属于投资计划。' : 'Initial capital and monthly contributions belong to an investment plan.'} action={<button type="button" className="button button-primary" onClick={() => navigate('/plan')}>{isZh ? '前往定投计划' : 'Open plan'} <ArrowRight size={15} /></button>} /></Panel></div>
  if (analysis.isLoading) return <div className="page contributions-page"><div className="page-intro"><div><span className="page-eyebrow">{isZh ? '资金批次' : 'Capital batches'}</span><h1>{isZh ? '投入分析' : 'Contributions'}</h1></div></div><LoadingBlock lines={10} /></div>
  if (analysis.isError || !analysis.data) return <div className="page"><ErrorState onRetry={() => void analysis.refetch()} /></div>

  const { data, meta } = analysis.data
  return <div className="page contributions-page">
    <div className="page-intro"><div><span className="page-eyebrow">{isZh ? '资金批次' : 'Capital batches'}</span><h1>{isZh ? '投入分析' : 'Contributions'}</h1><p>{isZh ? '把初始资金和每个月定投分开，看每批钱在市场里待了多久、现在赚了多少。' : 'Separate initial capital from monthly DCA and track how long each batch has been invested and how it has performed.'}</p></div><div className="page-actions"><button type="button" className="button button-secondary" onClick={() => navigate('/plan')}><WalletCards size={15} />{isZh ? '定投计划' : 'DCA plan'}</button></div></div>
    <DataStateBanner status={meta.status} message={meta.message} source={meta.source} asOf={data.asOf || meta.asOf} retrievedAt={meta.retrievedAt} />

    <div className="contribution-total-strip">
      <span><small>{isZh ? '累计实际投入' : 'Total actual contributions'}</small><strong>{formatMoney(data.totalInvested)}</strong></span>
      <span><small>{isZh ? '初始资金' : 'Initial capital'}</small><strong>{formatMoney(data.initial.principal)}</strong></span>
      <span><small>{isZh ? '定投批次' : 'DCA batches'}</small><strong>{data.dca.batchCount} {isZh ? '个月' : 'months'}</strong></span>
    </div>

    <div className="contribution-summary-grid">
      <SummaryPanel title={isZh ? '初始资金' : 'Initial capital'} bucket={data.initial} isZh={isZh} detail={isZh ? '来自实际 BUY 交易' : 'From actual BUY transactions'} />
      <SummaryPanel title={isZh ? '定投资金' : 'DCA capital'} bucket={data.dca} isZh={isZh} detail={isZh ? `${data.dca.batchCount} 个实际投入月份` : `${data.dca.batchCount} funded months`} />
    </div>

    {decimal(data.unclassifiedAmount).gt(0) ? <Panel title={isZh ? '未归类买入' : 'Unclassified buys'} detail={isZh ? `${formatMoney(data.unclassifiedAmount)} 尚未计入初始资金或定投资金` : `${formatMoney(data.unclassifiedAmount)} is not assigned to initial capital or DCA`} className="contribution-unclassified-panel">
      <p className="contribution-help">{isZh ? `只有投资计划开始日 ${plan.startDate} 的 BUY 才能归为初始资金；其他日期不会允许补记为初始资金。` : `Only BUYs on the investment plan start date (${plan.startDate}) can be marked as initial capital.`}</p>
      <div className="unclassified-list">{data.unclassifiedBuys.map((item) => {
        const canClassifyInitial = item.tradeDate === plan.startDate
        return <div className="unclassified-row" key={item.transactionId}><span><strong>{item.symbol}</strong><small>{formatDate(item.tradeDate)}</small></span><strong>{formatMoney(item.principal)}</strong><button type="button" className="button button-ghost button-small" disabled={classify.isPending || !canClassifyInitial} title={!canClassifyInitial ? (isZh ? `仅 ${plan.startDate} 可归为初始资金` : `Initial capital is only allowed on ${plan.startDate}`) : undefined} onClick={() => classify.mutate(item.transactionId)}>{canClassifyInitial ? (isZh ? '归为初始资金' : 'Mark as initial') : (isZh ? '非开始日' : 'Not start date')}</button></div>
      })}</div>
      {classify.error instanceof Error ? <p className="form-alert" role="alert">{classify.error.message}</p> : null}
    </Panel> : null}

    <Panel title={isZh ? '投入批次' : 'Contribution batches'} detail={isZh ? '收益率为累计 ROI，不做年化；股息暂不计入。' : 'Returns are cumulative ROI, not annualized. Dividends are excluded for now.'} className="contribution-batches-panel" flush>
      {data.batches.length ? <div className="data-table-wrap"><table className="data-table contribution-table"><thead><tr><th>{isZh ? '批次' : 'Batch'}</th><th>{isZh ? '本金' : 'Principal'}</th><th>{isZh ? '平均在场' : 'Market age'}</th><th>{isZh ? '批次价值' : 'Batch value'}</th><th>{isZh ? '收益' : 'P/L'}</th><th>{isZh ? '收益率' : 'Return'}</th><th>{isZh ? '数据' : 'Data'}</th></tr></thead><tbody>{data.batches.map((batch) => <tr key={`${batch.type}-${batch.period ?? 'initial'}`}><td><span className="batch-label"><Layers3 size={14} /><strong>{batch.type === 'INITIAL' ? (isZh ? '初始资金' : 'Initial') : batch.period ? formatPeriod(batch.period) : 'DCA'}</strong></span></td><td>{formatMoney(batch.principal)}</td><td>{batch.averageMarketDays} {isZh ? '天' : 'days'}</td><td><strong>{formatMoney(batch.value)}</strong></td><td className={trendClass(batch.pnl)}>{formatSignedMoney(batch.pnl)}</td><td className={trendClass(batch.returnRate)}>{formatSignedPercent(batch.returnRate)}</td><td><StatusBadge status={batch.dataStatus} compact /></td></tr>)}</tbody></table></div> : <EmptyState title={isZh ? '还没有已归类的投入' : 'No classified contributions yet'} detail={isZh ? '关联到 DCA 周期的 BUY 会自动形成月度批次；只有计划开始日的建仓 BUY 可以归为初始资金。' : 'BUYs linked to DCA cycles become monthly batches automatically; only opening-day BUYs can be marked as initial capital.'} />}
    </Panel>
  </div>
}
