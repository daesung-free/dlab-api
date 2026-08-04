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
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return SATURDAY;
        }
        return dow == DayOfWeek.SUNDAY ? SUNDAY : WEEKDAY;
    }
}
