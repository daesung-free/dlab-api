package com.dlab.domain.holiday.entity;

/**
 * 휴일 종류. 계산 로직은 종류를 구분하지 않지만(전부 똑같이 제외),
 * 관리 화면에서 자동 수집분과 수기 등록분을 구분해야 해서 남긴다.
 */
public enum HolidayType {

    /** 법정공휴일. */
    PUBLIC,

    /** 대체공휴일. 적용 대상이 해마다 확대돼 왔다. */
    SUBSTITUTE,

    /** 임시공휴일. 규칙 없이 정부가 지정한다 — 반드시 수기 등록이 필요한 유형. */
    TEMPORARY,

    /** 학원 자체 휴일(개원기념일 등). 지점별로 다를 수 있다. */
    ACADEMY
}
