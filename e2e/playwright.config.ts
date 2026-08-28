import { defineConfig, devices, type Page } from '@playwright/test'

export const e2eUser = process.env.DCA_E2E_USERNAME ?? 'e2e'
export const e2ePassword = process.env.DCA_E2E_PASSWORD ?? 'sa08-ci-password'
export const mockUrl = process.env.DCA_E2E_MOCK_URL ?? 'http://127.0.0.1:38081'

process.env.PLAYWRIGHT_CHROMIUM_USE_HEADLESS_SHELL = '0'

export const FIXTURE_MARKERS = [
  '28421.62',
  '24.7884',
  '15376.20',
  '620.21',
  '25180.39',
  '29600.00',
  'Demo data',
  'dca-terminal-fixture-state',
  'fixtureInstruments',
]

export async function login(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('dca-language', 'en')
  })
  await page.goto('/login')
  await page.getByLabel('Username').fill(e2eUser)
  await page.getByLabel('Password').fill(e2ePassword)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL((url) => url.pathname === '/' || url.pathname === '')
}

export async function assertNoFixtureLeak(page: Page): Promise<void> {
  const html = await page.content()
  for (const marker of FIXTURE_MARKERS) {
    if (html.includes(marker)) {
      throw new Error(`fixture marker leaked into the page: ${marker}`)
    }
  }
}

export async function setMockChart(mode: string): Promise<void> {
  const response = await fetch(`${mockUrl}/__e2e/mode`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ chart: mode }),
  })
  if (!response.ok) {
    throw new Error(`mock mode ${mode} failed: HTTP ${response.status}`)
  }
}

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: '/tmp/dca-e2e-report' }]],
  outputDir: process.env.PLAYWRIGHT_OUTPUT_DIR ?? '/tmp/dca-e2e-results',
  grep: process.env.DCA_E2E_SUITE === 'smoke' ? /@smoke/ : undefined,
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chromium',
    headless: true,
    baseURL: process.env.DCA_E2E_BASE_URL ?? 'http://127.0.0.1:38080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
})
