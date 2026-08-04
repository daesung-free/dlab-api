package com.dlab.common.holiday;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 급식 가능일 계산. 여기가 틀리면 <b>학생이 신청 못 하는 날에 신청하거나
 * 신청 가능한 날이 회색으로 막힌다</b> — 앱은 서버가 내려준 결과를 그대로 렌더하므로
 * 화면에서는 원인을 찾을 수 없다.
 */
class HolidayCalendarTest {

    private static final Long ACADEMY = 7L;

    /** 테스트용 고정 집합. 인터페이스로 둔 덕에 DB 없이 계산만 검증한다. */
    private HolidayCalendar calendarWith(Set<LocalDate> holidays) {
        HolidayProvider provider = new HolidayProvider() {
            @Override
            public boolean isHoliday(Long academyId, LocalDate date) {
                return holidays.contains(date);
            }

            @Override
            public Set<LocalDate> holidaysIn(Long academyId, LocalDate from, LocalDate to) {
                return holidays.stream()
                        .filter(d -> !d.isBefore(from) && !d.isAfter(to))
                        .collect(java.util.stream.Collectors.toSet());
            }
        };
        return new HolidayCalendar(provider);
    }

    @Test
    @DisplayName("급식 가능일에서 주말이 빠진다")
    void mealExcludesWeekend() {
        // 2026-08은 1일(토)로 시작한다
        var dates = calendarWith(Set.of()).mealAvailableDates(ACADEMY, YearMonth.of(2026, 8));

        assertThat(dates)
                .doesNotContain(LocalDate.of(2026, 8, 1))   // 토
                .doesNotContain(LocalDate.of(2026, 8, 2))   // 일
                .contains(LocalDate.of(2026, 8, 3));        // 월
        assertThat(dates).noneMatch(d ->
                d.getDayOfWeek().getValue() >= 6);
    }

    @Test
    @DisplayName("급식 가능일에서 공휴일이 빠진다")
    void mealExcludesHoliday() {
        LocalDate liberation = LocalDate.of(2026, 8, 17);   // 월요일로 가정한 임시공휴일
        var dates = calendarWith(Set.of(liberation))
                .mealAvailableDates(ACADEMY, YearMonth.of(2026, 8));

        assertThat(dates).doesNotContain(liberation);
    }

    @Test
    @DisplayName("★ 학습계획·출결은 주말을 빼지 않는다 — 이 학원은 토요일에도 운영한다")
    void weekendKeptWhenNotExcluded() {
        LocalDate saturday = LocalDate.of(2026, 8, 1);

        var withWeekend = calendarWith(Set.of()).operatingDates(
                ACADEMY, saturday, saturday.plusDays(2), false);

        assertThat(withWeekend).contains(saturday);
        assertThat(withWeekend).hasSize(3);
    }

    @Test
    @DisplayName("주말을 안 빼더라도 공휴일은 뺀다")
    void holidayExcludedEvenWithWeekend() {
        LocalDate sunday = LocalDate.of(2026, 8, 2);

        var dates = calendarWith(Set.of(sunday)).operatingDates(
                ACADEMY, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), false);

        assertThat(dates).doesNotContain(sunday).hasSize(2);
    }

    @Test
    @DisplayName("시작일이 종료일보다 뒤면 빈 목록 — 예외를 던지지 않는다")
    void invertedRangeIsEmpty() {
        var dates = calendarWith(Set.of()).operatingDates(
                ACADEMY, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1), true);

        assertThat(dates).isEmpty();
    }

    @Test
    @DisplayName("단일 날짜 판정 — 주말 제외 옵션이 반영된다")
    void singleDayCheck() {
        LocalDate saturday = LocalDate.of(2026, 8, 1);
        LocalDate monday = LocalDate.of(2026, 8, 3);
        HolidayCalendar calendar = calendarWith(Set.of(monday));

        assertThat(calendar.isOperatingDay(ACADEMY, saturday, true)).isFalse();   // 주말
        assertThat(calendar.isOperatingDay(ACADEMY, saturday, false)).isTrue();   // 주말 허용
        assertThat(calendar.isOperatingDay(ACADEMY, monday, false)).isFalse();    // 공휴일
        assertThat(calendar.isWeekend(saturday)).isTrue();
    }

    @Test
    @DisplayName("기간 조회를 한 번만 한다 — 날짜마다 부르면 한 달에 30번 나간다")
    void rangeQueriedOnce() {
        int[] callCount = {0};
        HolidayProvider counting = new HolidayProvider() {
            @Override
            public boolean isHoliday(Long academyId, LocalDate date) {
                return false;
            }

            @Override
            public Set<LocalDate> holidaysIn(Long academyId, LocalDate from, LocalDate to) {
                callCount[0]++;
                return Set.of();
            }
        };

        new HolidayCalendar(counting).mealAvailableDates(ACADEMY, YearMonth.of(2026, 8));

        assertThat(callCount[0]).isEqualTo(1);
    }
}
