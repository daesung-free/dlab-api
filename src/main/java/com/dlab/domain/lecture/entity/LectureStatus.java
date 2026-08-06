package com.dlab.domain.lecture.entity;

/**
 * 특강 진행 상태 (DSA 실사의 "상태별 필터").
 *
 * <p><b>{@code visible}(앱 노출)과 다른 축이다.</b> 접수를 닫아도 목록에는 보여야 하는 경우가
 * 있고, 준비 중인 특강을 접수 열기 전에 숨겨야 하는 경우도 있다.
 */
public enum LectureStatus {
    /** 준비 중 — 신청 불가. */
    DRAFT,
    /** 접수 중. */
    OPEN,
    /** 접수 마감 — 진행은 남아 있을 수 있다. */
    CLOSED,
    /** 종료. */
    DONE,
    CANCELED;

    /** 이 상태에서 신청을 받는가. */
    public boolean acceptsApplication() {
        return this == OPEN;
    }
}
