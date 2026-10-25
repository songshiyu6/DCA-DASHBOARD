import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { SettingsPage } from './SettingsPage'

const api = vi.hoisted(() => ({ getSettings: vi.fn(), updateSettings: vi.fn() }))
vi.mock('../lib/api', () => ({ api }))
const result = { data: { theme: 'DARK', primaryProvider: 'YAHOO', fallbackProvider: 'NONE' }, meta: { status: 'FRESH' } }

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><SettingsPage /></QueryClientProvider>)
  return client
}
beforeEach(() => { vi.resetAllMocks(); api.getSettings.mockResolvedValue(result) })

it('preserves unsaved selections when background settings refresh', async () => {
  const client = renderPage()
  fireEvent.change(await screen.findByLabelText('Primary provider'), { target: { value: 'TWELVE_DATA' } })
  await act(async () => {
    client.setQueryData(['settings'], { ...result, data: { ...result.data, theme: 'LIGHT' } })
    await new Promise((resolve) => setTimeout(resolve, 20))
  })
  expect(screen.getByLabelText('Primary provider')).toHaveValue('TWELVE_DATA')
})

it('shows a failed save and keeps the draft available for retry', async () => {
  api.updateSettings.mockRejectedValue(new Error('Provider settings could not be saved'))
  renderPage()
  fireEvent.change(await screen.findByLabelText('Primary provider'), { target: { value: 'TWELVE_DATA' } })
  fireEvent.click(screen.getByRole('button', { name: /Save/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Provider settings could not be saved')
  expect(screen.getByLabelText('Primary provider')).toHaveValue('TWELVE_DATA')
})
