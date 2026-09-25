package com.dlab.domain.search.entity;

/**
 * 어느 화면의 검색조건인가.
 *
 * <p><b>화면마다 값을 나눈다.</b> 하나로 두면 출결 화면에서 저장한 조건이 학생 검색에 뜨고,
 * 조건 모양이 화면마다 달라 불러오는 순간 화면이 깨진다.
 *
 * <p>서버는 조건 내용을 해석하지 않는다 — 화면이 만든 JSON 을 그대로 되돌려줄 뿐이다.
 * 그래서 화면이 늘어도 여기 값만 추가하면 된다.
 */
public enum SearchType {

    /** 학원생 검색 (조건 12개). */
    STUDENT,

    /** 출결 현황. */
    ATTENDANCE,

    /** 상벌점. */
    PENALTY,

    /** 수납 현황. */
    RECEIPT_STATUS,

    /** 결제 관리. */
    PAYMENT,

    /** 교무업무 명단 조회. */
    ROSTER,

    /** 금일 수정 이력(감사 로그). */
    AUDIT_LOG,

    /** 좌석 이탈. */
    SEAT_LEAVE
}
