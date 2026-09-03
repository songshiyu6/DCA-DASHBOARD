const DAY_MS = 86_400_000

const AD_HOC_FULL_DAY_CLOSURES = new Set([
  '2001-09-11',
  '2001-09-12',
  '2001-09-13',
  '2001-09-14',
  '2004-06-11',
  '2007-01-02',
  '2012-10-29',
  '2012-10-30',
  '2018-12-05',
  '2025-01-09',
])

function parseUtcDay(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) return null
  return parsed
}

function formatUtcDay(value: Date): string {
  return `${value.getUTCFullYear()}-${String(value.getUTCMonth() + 1).padStart(2, '0')}-${String(value.getUTCDate()).padStart(2, '0')}`
}

function addDays(value: Date, days: number): Date {
  return new Date(value.getTime() + days * DAY_MS)
}

function nthWeekdayOfMonth(year: number, month: number, weekday: number, nth: number): Date {
  const first = new Date(Date.UTC(year, month, 1))
  const offset = (weekday - first.getUTCDay() + 7) % 7
  return new Date(Date.UTC(year, month, 1 + offset + (nth - 1) * 7))
}

function lastWeekdayOfMonth(year: number, month: number, weekday: number): Date {
  const last = new Date(Date.UTC(year, month + 1, 0))
  const offset = (last.getUTCDay() - weekday + 7) % 7
  return new Date(Date.UTC(year, month, last.getUTCDate() - offset))
}

function observedFixedHoliday(year: number, month: number, day: number, observeSaturday = true): Date {
  const holiday = new Date(Date.UTC(year, month, day))
  if (holiday.getUTCDay() === 0) return addDays(holiday, 1)
  if (holiday.getUTCDay() === 6 && observeSaturday) return addDays(holiday, -1)
  return holiday
}

function easterSunday(year: number): Date {
  const a = year % 19
  const b = Math.floor(year / 100)
  const c = year % 100
  const d = Math.floor(b / 4)
  const e = b % 4
  const f = Math.floor((b + 8) / 25)
  const g = Math.floor((b - f + 1) / 3)
  const h = (19 * a + b - d - g + 15) % 30
  const i = Math.floor(c / 4)
  const k = c % 4
  const l = (32 + 2 * e + 2 * i - h - k) % 7
  const m = Math.floor((a + 11 * h + 22 * l) / 451)
  const month = Math.floor((h + l - 7 * m + 114) / 31) - 1
  const day = ((h + l - 7 * m + 114) % 31) + 1
  return new Date(Date.UTC(year, month, day))
}

function regularFullDayClosures(year: number): Set<string> {
  const closures = new Set<string>()
  const add = (date: Date) => closures.add(formatUtcDay(date))

  // NYSE/Nasdaq do not shift New Year's Day back to Friday when Jan 1 is Saturday.
  add(observedFixedHoliday(year, 0, 1, false))
  add(nthWeekdayOfMonth(year, 0, 1, 3)) // Martin Luther King Jr. Day
  add(nthWeekdayOfMonth(year, 1, 1, 3)) // Washington's Birthday / Presidents Day
  add(addDays(easterSunday(year), -2)) // Good Friday
  add(lastWeekdayOfMonth(year, 4, 1)) // Memorial Day
  if (year >= 2022) add(observedFixedHoliday(year, 5, 19)) // Juneteenth
  add(observedFixedHoliday(year, 6, 4)) // Independence Day
  add(nthWeekdayOfMonth(year, 8, 1, 1)) // Labor Day
  add(nthWeekdayOfMonth(year, 10, 4, 4)) // Thanksgiving Day
  add(observedFixedHoliday(year, 11, 25)) // Christmas Day

  return closures
}

const closureCache = new Map<number, Set<string>>()

export function isUsMarketTradingDay(value: string): boolean {
  const date = parseUtcDay(value)
  if (!date) return false
  const weekday = date.getUTCDay()
  if (weekday === 0 || weekday === 6) return false

  const day = formatUtcDay(date)
  if (AD_HOC_FULL_DAY_CLOSURES.has(day)) return false

  const year = date.getUTCFullYear()
  let closures = closureCache.get(year)
  if (!closures) {
    closures = regularFullDayClosures(year)
    closureCache.set(year, closures)
  }
  return !closures.has(day)
}
