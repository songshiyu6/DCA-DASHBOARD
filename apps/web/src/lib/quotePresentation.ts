import type { QuoteSession } from '../types'

export function quoteSessionLabel(session: QuoteSession | undefined, isZh: boolean): string {
  const value = session ?? 'UNKNOWN'
  if (isZh) {
    switch (value) {
      case 'REGULAR': return '常规盘'
      case 'PRE_MARKET': return '盘前'
      case 'EXTENDED': return '延长时段'
      case 'POST_MARKET': return '盘后'
      case 'OVERNIGHT': return '夜盘'
      case 'REGULAR_FALLBACK': return '常规价（降级）'
      default: return '时段未知'
    }
  }
  switch (value) {
    case 'REGULAR': return 'Regular'
    case 'PRE_MARKET': return 'Pre-market'
    case 'EXTENDED': return 'Extended'
    case 'POST_MARKET': return 'Post-market'
    case 'OVERNIGHT': return 'Overnight'
    case 'REGULAR_FALLBACK': return 'Regular fallback'
    default: return 'Session unknown'
  }
}
