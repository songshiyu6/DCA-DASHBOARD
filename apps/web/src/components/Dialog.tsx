import { useEffect, useRef, type ReactNode } from 'react'

const focusableSelector = [
  'button:not([disabled])',
  '[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

interface DialogProps {
  children: ReactNode
  labelledBy: string
  describedBy?: string
  className?: string
  onClose: () => void
}

export function Dialog({ children, labelledBy, describedBy, className = '', onClose }: DialogProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = dialogRef.current
    const focusFrame = requestAnimationFrame(() => {
      const firstFocusable = dialog?.querySelector<HTMLElement>(focusableSelector)
      ;(firstFocusable ?? dialog)?.focus()
    })

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeRef.current()
        return
      }
      if (event.key !== 'Tab' || !dialog) return
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector))
      if (focusable.length === 0) {
        event.preventDefault()
        dialog.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      cancelAnimationFrame(focusFrame)
      document.removeEventListener('keydown', handleKeyDown)
      if (previouslyFocused?.isConnected) previouslyFocused.focus()
    }
  }, [])

  return <div className="modal-backdrop" role="presentation"><div ref={dialogRef} className={`modal ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby={labelledBy} {...(describedBy ? { 'aria-describedby': describedBy } : {})} tabIndex={-1}>{children}</div></div>
}
