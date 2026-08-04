package com.dlab.common.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 휴일을 제외한 실제 운영일 계산.
 *
 * <p>급식 가능일이 대표 용도다 — 요구사항 3시트 ② {@code MealPolicy.availableDates}는
 * <b>"주말 + 공휴일 제외"</b>로 정의돼 있다.
 *
 * <p><b>주말 제외 여부를 옵션으로 둔 이유:</b> 이 학원은 토요일에도 운영한다
 * (시안 {@code plan-3}이 토요일 학습계획이고, 교시 마스터에도 주말 구성이 따로 있다).
 * 급식만 주말을 빼는 것이라 계산기 자체에 주말 제외를 박아두면 다른 도메인에서 못 쓴다.
 *
 * <p><b>앱이 자체 판정하지 못하게 서버가 내려준다.</b> 앱 요구사항 A-9에
 * "서버 mealPolicy 기준으로 렌더, 앱 자체 판정 금지"가 명시돼 있다 —
 * 임시공휴일이 추가되면 앱을 배포해야 반영되는 상황을 막으려는 것이다.
 */
@Component
@RequiredArgsConstructor
public class HolidayCalendar {

    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final HolidayProvider holidayProvider;

    /** 급식 가능일 — 주말과 공휴일을 뺀 날짜. */
    public List<LocalDate> mealAvailableDates(Long academyId, YearMonth month) {
        return operatingDates(academyId, month.atDay(1), month.atEndOfMonth(), true);
    }

    /**
     * 운영일 목록.
     *
     * @param excludeWeekend 주말도 제외할지. 급식은 {@code true}, 학습계획·출결은 {@code false}
     */
    public List<LocalDate> operatingDates(Long academyId, LocalDate from, LocalDate to,
                                          boolean excludeWeekend) {
        if (from.isAfter(to)) {
            return List.of();
        }
        // 날짜마다 조회하면 한 달에 30번이 나간다. 기간 조회 한 번으로 끝낸다.
        Set<LocalDate> holidays = holidayProvider.holidaysIn(academyId, from, to);

        List<LocalDate> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (excludeWeekend && WEEKEND.contains(date.getDayOfWeek())) {
                continue;
            }
            if (holidays.contains(date)) {
                continue;
            }
            result.add(date);
        }
        return result;
    }

    /** 그 날 운영하는가. */
    public boolean isOperatingDay(Long academyId, LocalDate date, boolean excludeWeekend) {
        if (excludeWeekend && WEEKEND.contains(date.getDayOfWeek())) {
            return false;
        }
        return !holidayProvider.isHoliday(academyId, date);
    }

    public boolean isWeekend(LocalDate date) {
        return WEEKEND.contains(date.getDayOfWeek());
    }
}
