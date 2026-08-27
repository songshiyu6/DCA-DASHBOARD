package com.dca.terminal.marketdata;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;

/**
 * Trading-date rules needed to distinguish an expected daily close from a
 * weekend or a full-day US market holiday.
 */
public final class MarketCalendar {
    private MarketCalendar() { }

    public static LocalDate latestExpectedTradingDate(LocalDate date) {
        if (date == null) return null;
        LocalDate candidate = date;
        while (!isTradingDay(candidate)) candidate = candidate.minusDays(1);
        return candidate;
    }

    static boolean isTradingDay(LocalDate date) {
        if (date == null || date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        return !isHoliday(date);
    }

    private static boolean isHoliday(LocalDate date) {
        for (int year = date.getYear() - 1; year <= date.getYear() + 1; year++) {
            if (observedFixedHoliday(LocalDate.of(year, Month.JANUARY, 1), date)
                    || observedFixedHoliday(LocalDate.of(year, Month.DECEMBER, 25), date)
                    || observedFixedHoliday(LocalDate.of(year, Month.JULY, 4), date)
                    || (year >= 2022 && observedFixedHoliday(LocalDate.of(year, Month.JUNE, 19), date))) {
                return true;
            }

            LocalDate easter = easterSunday(year);
            if (date.equals(easter.minusDays(2))) return true; // Good Friday
            if (date.equals(thirdMonday(year, Month.JANUARY))
                    || date.equals(thirdMonday(year, Month.FEBRUARY))
                    || date.equals(lastMonday(year, Month.MAY))
                    || date.equals(firstMonday(year, Month.SEPTEMBER))
                    || date.equals(fourthThursday(year, Month.NOVEMBER))) {
                return true;
            }
        }
        return false;
    }

    private static boolean observedFixedHoliday(LocalDate holiday, LocalDate date) {
        LocalDate observed = switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
        return date.equals(observed);
    }

    private static LocalDate thirdMonday(int year, Month month) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY));
    }

    private static LocalDate lastMonday(int year, Month month) {
        return LocalDate.of(year, month, month.length(false)).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY));
    }

    private static LocalDate firstMonday(int year, Month month) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
    }

    private static LocalDate fourthThursday(int year, Month month) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
    }

    // Gregorian computus; the market holiday is the Friday before Easter Sunday.
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
