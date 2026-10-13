package com.dlab.domain.period.entity;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 교시 구성이 갈리는 요일 구분.
 *
 * <p><b>이 학원은 토요일에도 운영한다</b>(시안 plan-3). 토요일은 평일과 교시 구성이 달라
 * (0교시·2교시가 없다) 같은 표를 쓰면 안 된다. 일요일은 교시를 두지 않는다 —
 * 그래서 일요일 태깅은 시간표가 없어 자동판별이 불가하고, 이게 DSA {@code code 113}의 정체다.
 */
public enum DayType {

    WEEKDAY,
    SATURDAY,
    SUNDAY;

    public static DayType of(LocalDate date) {
        return of(date.getDayOfWeek());
    }

    /**
     * 요일만으로 구분한다.
     *
     * <p>시간표 편성처럼 <b>날짜가 아니라 요일 칸</b>을 다루는 쪽이 쓴다. 날짜 버전과 규칙을
     * 둘로 두면 <b>한쪽만 고쳐져</b> 「같은 토요일인데 출결은 토요일 교시, 시간표는 평일 교시」가
     * 된다 — 그래서 날짜 버전이 이쪽에 위임한다.
     */
    public static DayType of(DayOfWeek dayOfWeek) {
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return SATURDAY;
        }
        return dayOfWeek == DayOfWeek.SUNDAY ? SUNDAY : WEEKDAY;
    }
}
