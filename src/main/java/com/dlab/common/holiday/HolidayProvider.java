package com.dlab.common.holiday;

import java.time.LocalDate;
import java.util.Set;

/**
 * 공휴일 조회. <b>구현체를 갈아끼울 수 있게 인터페이스로 둔다</b> —
 * 지금은 DB에서 읽지만 공공데이터포털 특일정보 API를 붙일 여지가 있고,
 * 테스트에서는 고정 집합을 주입해야 한다.
 *
 * <p>{@code academyId}를 받는 이유: 법정공휴일 외에 <b>지점 자체 휴일</b>(개원기념일 등)이
 * 있을 수 있다. {@code null}이면 전 지점 공통 휴일만 본다.
 */
public interface HolidayProvider {

    /** 그 날이 휴일인가. 법정공휴일 + 해당 지점 자체 휴일을 함께 본다. */
    boolean isHoliday(Long academyId, LocalDate date);

    /**
     * 기간 내 휴일을 한 번에 가져온다.
     *
     * <p>급식 신청은 한 달치를 통째로 계산하므로, 날짜마다
     * {@link #isHoliday}를 부르면 30번 조회한다. 기간 조회를 기본으로 쓸 것.
     *
     * @param from 포함
     * @param to   포함
     */
    Set<LocalDate> holidaysIn(Long academyId, LocalDate from, LocalDate to);
}
