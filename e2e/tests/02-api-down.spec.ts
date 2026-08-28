import { expect, test } from '@playwright/test'
import { assertNoFixtureLeak, FIXTURE_MARKERS, login } from '../playwright.config'

test.describe('E2E-02 API down without fixture fallback', () => {
  test('shows error/retry and never leaks fixture account data', async ({ page }) => {
    const leakedBodies: string[] = []
    page.on('response', async (response) => {
      const url = response.url()
      if (!url.includes('/api/')) return
      try {
        leakedBodies.push(await response.text())
      } catch {
        leakedBodies.push('')
      }
    })

    await login(page)
    await expect(page.getByRole('heading').first()).toBeVisible()
    await assertNoFixtureLeak(page)

    await page.route('**/api/v1/**', async (route) => {
      if (route.request().url().includes('/api/v1/auth/')) {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Service Unavailable', status: 503, detail: 'API unavailable' }),
      })
    })
    await page.reload()

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible()
    await assertNoFixtureLeak(page)
    const html = await page.content()
    for (const marker of FIXTURE_MARKERS) {
      expect(html, marker).not.toContain(marker)
      for (const body of leakedBodies) {
        expect(body, marker).not.toContain(marker)
      }
    }
  })
})
