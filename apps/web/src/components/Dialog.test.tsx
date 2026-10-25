import { act, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { Dialog } from './Dialog'

it('does not steal focus when the user starts typing before the initial animation frame', () => {
  let frame!: FrameRequestCallback
  const request = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    frame = callback
    return 1
  })
  try {
    render(<Dialog labelledBy="test-dialog-title" onClose={() => {}}>
      <button type="button">Close</button>
      <h2 id="test-dialog-title">Import</h2>
      <textarea aria-label="CSV" />
    </Dialog>)
    screen.getByRole('textbox').focus()
    act(() => frame(0))
    expect(screen.getByRole('textbox')).toHaveFocus()
  } finally {
    request.mockRestore()
  }
})
