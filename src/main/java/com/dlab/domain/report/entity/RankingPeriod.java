package com.dlab.domain.report.entity;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 랭킹 집계 기간.
 *
 * <p><b>기간의 시작일을 여기서 계산한다</b> — 배치와 조회가 같은 규칙을 써야
 * 적재한 행을 찾을 수 있다. 주 시작을 한쪽은 월요일, 다른 쪽은 일요일로 보면
 * 랭킹이 조용히 비어 보인다.
 */
public enum RankingPeriod {

    DAILY {
        @Override
        public LocalDate startOf(LocalDate date) {
            return date;
        }
    },

    /** 월~일. 학원 주간이 월요일 시작이다. */
    WEEKLY {
        @Override
        public LocalDate startOf(LocalDate date) {
            return date.with(DayOfWeek.MONDAY);
        }
    },

    MONTHLY {
        @Override
        public LocalDate startOf(LocalDate date) {
            return date.withDayOfMonth(1);
        }
    };

    public abstract LocalDate startOf(LocalDate date);

    /** 기간의 마지막 날(포함). */
    public LocalDate endOf(LocalDate date) {
        return switch (this) {
            case DAILY -> date;
            case WEEKLY -> startOf(date).plusDays(6);
            case MONTHLY -> date.withDayOfMonth(date.lengthOfMonth());
        };
    }
}
